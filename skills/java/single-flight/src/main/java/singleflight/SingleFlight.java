package singleflight;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Coalesces concurrent calls for the same key into one execution ("single flight").
 *
 * <p>Results are never cached: once a call completes its key is forgotten. Each caller
 * receives its own dependent future, so cancelling one caller does not cancel the others.
 *
 * @param <K> key type (needs a sensible {@code equals}/{@code hashCode})
 * @param <V> result type
 */
public final class SingleFlight<K, V> {
    private final Executor executor;
    private final Object lock = new Object();
    private final Map<K, CompletableFuture<V>> inFlight = new HashMap<>();

    public SingleFlight(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Runs {@code task} unless a call for {@code key} is already in flight, then joins it. */
    public CompletableFuture<V> run(K key, Callable<V> task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        CompletableFuture<V> shared;
        boolean leader = false;
        synchronized (lock) {
            shared = inFlight.get(key);
            if (shared == null) {
                shared = new CompletableFuture<>();
                inFlight.put(key, shared);
                leader = true;
            }
        }
        if (leader) {
            start(key, shared, task);
        }
        return shared.copy();
    }

    /** Number of distinct keys currently in flight. */
    public int inFlight() {
        synchronized (lock) {
            return inFlight.size();
        }
    }

    private void start(K key, CompletableFuture<V> shared, Callable<V> task) {
        try {
            executor.execute(() -> {
                try {
                    finish(key, shared, task.call(), null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    finish(key, shared, null, e);
                } catch (Throwable t) {
                    finish(key, shared, null, t);
                }
            });
        } catch (RuntimeException e) {
            finish(key, shared, null, e);
        }
    }

    /** Forgets the key first, then completes, so continuations may start a fresh flight. */
    private void finish(K key, CompletableFuture<V> shared, V value, Throwable error) {
        synchronized (lock) {
            inFlight.remove(key, shared);
        }
        if (error != null) {
            shared.completeExceptionally(error);
        } else {
            shared.complete(value);
        }
    }
}
