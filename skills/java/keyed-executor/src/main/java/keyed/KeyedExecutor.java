package keyed;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks with the same key serially in submission order, and tasks with different keys in
 * parallel on a caller-supplied {@link Executor}.
 *
 * <p>Each key owns a FIFO queue. While a key has work, exactly one "turn" for it is scheduled on
 * the delegate; after running one task the turn re-submits itself, so a hot key cannot hog a
 * worker. A key is removed as soon as its queue drains.
 */
public final class KeyedExecutor {
    private final Executor delegate;
    private final Object lock = new Object();
    private final Map<Object, ArrayDeque<Runnable>> queues = new HashMap<>();
    private boolean shutdown;

    public KeyedExecutor(Executor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Queues {@code task} behind earlier tasks of {@code key}. */
    public <T> CompletableFuture<T> submit(Object key, Callable<T> task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable job = () -> {
            if (future.isCancelled()) {
                return;
            }
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        synchronized (lock) {
            if (shutdown) {
                throw new RejectedExecutionException("KeyedExecutor is shut down");
            }
            ArrayDeque<Runnable> queue = queues.get(key);
            if (queue != null) {
                queue.addLast(job);
                return future;
            }
            queue = new ArrayDeque<>();
            queue.addLast(job);
            queues.put(key, queue);
            try {
                delegate.execute(() -> runTurn(key));
            } catch (RuntimeException e) {
                queues.remove(key); // roll back: nothing stays queued
                lock.notifyAll();
                if (e instanceof RejectedExecutionException) {
                    throw e;
                }
                throw new RejectedExecutionException("delegate failed to accept the task", e);
            }
        }
        return future;
    }

    private void runTurn(Object key) {
        Runnable job;
        synchronized (lock) {
            job = queues.get(key).peekFirst();
        }
        try {
            job.run();
        } finally {
            synchronized (lock) {
                ArrayDeque<Runnable> queue = queues.get(key);
                queue.removeFirst();
                if (queue.isEmpty()) {
                    queues.remove(key);
                    lock.notifyAll();
                } else {
                    try {
                        delegate.execute(() -> runTurn(key));
                    } catch (RuntimeException e) {
                        // Delegate refuses further work: drop the key so waiters are not stuck.
                        queues.remove(key);
                        lock.notifyAll();
                        throw e;
                    }
                }
            }
        }
    }

    /** Number of keys that currently have queued or running tasks. */
    public int activeKeys() {
        synchronized (lock) {
            return queues.size();
        }
    }

    /** Refuses new tasks; tasks already accepted still run. */
    public void shutdown() {
        synchronized (lock) {
            shutdown = true;
        }
    }

    /** Waits until no key is active; returns false if {@code timeout} elapsed first. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (!queues.isEmpty()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, left);
            }
            return true;
        }
    }
}
