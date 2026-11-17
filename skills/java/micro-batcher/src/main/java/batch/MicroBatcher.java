package batch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Groups individually submitted items into batches processed by one bulk function.
 * A batch is dispatched when it reaches {@code maxSize} items or {@code maxWait} after its
 * first item, whichever comes first. Thread-safe; the bulk function never runs under the lock.
 *
 * @param <T> item type
 * @param <R> result type
 */
public final class MicroBatcher<T, R> {

    private record Entry<T, R>(T item, CompletableFuture<R> future) { }

    private final Function<List<T>, List<R>> bulk;
    private final int maxSize;
    private final Duration maxWait;
    private final Executor worker;
    private final Scheduler scheduler;

    private final Object lock = new Object();
    private List<Entry<T, R>> pending = new ArrayList<>();
    private long generation;          // identifies the current pending batch
    private Runnable cancelTimer = () -> { };
    private boolean closed;

    public MicroBatcher(Function<List<T>, List<R>> bulk, int maxSize, Duration maxWait,
                        Executor worker, Scheduler scheduler) {
        this.bulk = Objects.requireNonNull(bulk, "bulk");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(maxWait, "maxWait");
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1: " + maxSize);
        }
        if (maxWait.isNegative() || maxWait.isZero()) {
            throw new IllegalArgumentException("maxWait must be positive: " + maxWait);
        }
        this.maxSize = maxSize;
        this.maxWait = maxWait;
    }

    /** Queues an item; the future completes with its result once its batch has run. */
    public CompletableFuture<R> submit(T item) {
        Objects.requireNonNull(item, "item");
        CompletableFuture<R> future = new CompletableFuture<>();
        List<Entry<T, R>> toRun = null;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("batcher is closed");
            }
            pending.add(new Entry<>(item, future));
            if (pending.size() >= maxSize) {
                toRun = takePendingLocked();
            } else if (pending.size() == 1) {
                long gen = generation;
                cancelTimer = scheduler.schedule(() -> timerFired(gen), maxWait);
            }
        }
        if (toRun != null) {
            dispatch(toRun);
        }
        return future;
    }

    /** Dispatches whatever is pending right now (no-op when empty). */
    public void flush() {
        List<Entry<T, R>> toRun;
        synchronized (lock) {
            toRun = takePendingLocked();
        }
        dispatch(toRun);
    }

    /** Flushes, then rejects further submissions. Idempotent. */
    public void close() {
        List<Entry<T, R>> toRun;
        synchronized (lock) {
            closed = true;
            toRun = takePendingLocked();
        }
        dispatch(toRun);
    }

    /** Number of items waiting for dispatch. */
    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    private void timerFired(long gen) {
        List<Entry<T, R>> toRun;
        synchronized (lock) {
            if (gen != generation) {
                return; // stale timer: that batch was already dispatched
            }
            toRun = takePendingLocked();
        }
        dispatch(toRun);
    }

    /** Detaches the pending batch and invalidates its timer. Caller holds the lock. */
    private List<Entry<T, R>> takePendingLocked() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Entry<T, R>> batch = pending;
        pending = new ArrayList<>();
        generation++;
        Runnable cancel = cancelTimer;
        cancelTimer = () -> { };
        cancel.run();
        return batch;
    }

    private void dispatch(List<Entry<T, R>> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            worker.execute(() -> run(batch));
        } catch (RuntimeException e) {
            failAll(batch, e);
        }
    }

    private void run(List<Entry<T, R>> batch) {
        List<T> items = new ArrayList<>(batch.size());
        for (Entry<T, R> e : batch) {
            items.add(e.item());
        }
        List<R> results;
        try {
            results = bulk.apply(List.copyOf(items));
        } catch (Throwable t) {
            failAll(batch, t);
            return;
        }
        if (results == null || results.size() != batch.size()) {
            failAll(batch, new IllegalStateException("bulk function returned "
                    + (results == null ? "null" : results.size() + " results") + " for " + batch.size() + " items"));
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            batch.get(i).future().complete(results.get(i));
        }
    }

    private static <T, R> void failAll(List<Entry<T, R>> batch, Throwable cause) {
        for (Entry<T, R> e : batch) {
            e.future().completeExceptionally(cause);
        }
    }
}
