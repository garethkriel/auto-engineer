package conc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/** Small helpers for bounded, fail-fast parallel work. Running tasks are never interrupted. */
public final class Parallel {
    private Parallel() { }

    /**
     * Maps {@code items} through {@code fn} on {@code executor}, at most {@code maxConcurrency}
     * at a time, preserving input order. After the first failure no new item starts and the call
     * throws {@link ParallelException} without waiting for items that are still running.
     */
    public static <T, R> List<R> mapOrdered(Executor executor, List<? extends T> items,
            Function<? super T, ? extends R> fn, int maxConcurrency) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(fn, "fn");
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1: " + maxConcurrency);
        }
        Semaphore permits = new Semaphore(maxConcurrency);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        CompletableFuture<Void> failure = new CompletableFuture<>();
        List<CompletableFuture<R>> futures = new ArrayList<>(items.size());
        try {
            for (T item : items) {
                permits.acquire();
                if (firstFailure.get() != null) {
                    permits.release();
                    break;
                }
                CompletableFuture<R> cf = new CompletableFuture<>();
                futures.add(cf);
                try {
                    executor.execute(() -> runOne(item, fn, cf, firstFailure, failure, permits));
                } catch (RuntimeException e) { // e.g. RejectedExecutionException
                    permits.release();
                    throw e;
                }
            }
            if (firstFailure.get() == null) {
                CompletableFuture.anyOf(CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])),
                        failure).get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParallelException("interrupted while waiting", e);
        } catch (ExecutionException e) {
            throw new ParallelException("task failed", firstFailure.get() != null ? firstFailure.get() : e.getCause());
        }
        Throwable t = firstFailure.get();
        if (t != null) {
            throw new ParallelException("task failed", t);
        }
        List<R> out = new ArrayList<>(futures.size());
        for (CompletableFuture<R> cf : futures) {
            out.add(cf.join());
        }
        return out;
    }

    private static <T, R> void runOne(T item, Function<? super T, ? extends R> fn, CompletableFuture<R> cf,
            AtomicReference<Throwable> firstFailure, CompletableFuture<Void> failure, Semaphore permits) {
        try {
            if (firstFailure.get() != null) {
                cf.cancel(false); // skipped: a failure already happened
                return;
            }
            cf.complete(fn.apply(item));
        } catch (Throwable t) { // NOPMD: record anything the task throws
            firstFailure.compareAndSet(null, t);
            cf.completeExceptionally(t);
            failure.completeExceptionally(t);
        } finally {
            permits.release();
        }
    }

    /**
     * Completes with the first supplier result that succeeds. Failed suppliers are skipped; if all
     * fail the future fails with {@link AllFailedException}. Suppliers that have not started when a
     * winner exists are not run.
     */
    public static <T> CompletableFuture<T> firstSuccess(Executor executor,
            List<? extends Supplier<? extends T>> tasks) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("no tasks");
        }
        List<Supplier<? extends T>> copy = List.copyOf(tasks);
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(copy.size());
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (Supplier<? extends T> task : copy) {
            Runnable r = () -> {
                try {
                    if (!result.isDone()) {
                        result.complete(task.get());
                    }
                } catch (Throwable t) { // NOPMD: a failing supplier is just skipped
                    errors.add(t);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        result.completeExceptionally(new AllFailedException(new ArrayList<>(errors)));
                    }
                }
            };
            try {
                executor.execute(r);
            } catch (RejectedExecutionException e) {
                errors.add(e);
                if (remaining.decrementAndGet() == 0) {
                    result.completeExceptionally(new AllFailedException(new ArrayList<>(errors)));
                }
            }
        }
        return result;
    }

    /**
     * Returns a future mirroring {@code source}, or failing with {@link TimeoutException} once
     * {@code timeout} elapses. The source itself is left running; the timer is cancelled when the
     * source finishes first.
     */
    public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> source, Duration timeout,
            ScheduledExecutorService scheduler) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(scheduler, "scheduler");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("negative timeout: " + timeout);
        }
        CompletableFuture<T> out = new CompletableFuture<>();
        ScheduledFuture<?> timer = scheduler.schedule(
                () -> out.completeExceptionally(new TimeoutException("timed out after " + timeout)),
                timeout.toNanos(), TimeUnit.NANOSECONDS);
        source.whenComplete((v, ex) -> {
            timer.cancel(false);
            if (ex != null) {
                out.completeExceptionally(ex);
            } else {
                out.complete(v);
            }
        });
        return out;
    }
}
