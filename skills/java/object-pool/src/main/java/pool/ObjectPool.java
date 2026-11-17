package pool;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A bounded, blocking pool of reusable objects.
 *
 * <p>All bookkeeping happens under one lock, but user callbacks (factory, validator, destroyer)
 * always run outside it. {@code total} counts idle + borrowed + reserved-for-creation objects, so a slot
 * is reserved before the factory runs and returned if it fails.
 *
 * @param <T> pooled type
 */
public final class ObjectPool<T> implements AutoCloseable {
    private final int maxSize;
    private final Supplier<T> factory;
    private final Predicate<T> validator;
    private final Consumer<T> destroyer;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition slotFree = lock.newCondition();
    private final ArrayDeque<T> idle = new ArrayDeque<>();
    private final Set<T> borrowed = Collections.newSetFromMap(new IdentityHashMap<>());
    private int total;
    private boolean closed;

    /** Creates a pool whose objects are always valid and need no cleanup. */
    public ObjectPool(int maxSize, Supplier<T> factory) {
        this(maxSize, factory, t -> true, t -> { });
    }

    public ObjectPool(int maxSize, Supplier<T> factory, Predicate<T> validator, Consumer<T> destroyer) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1: " + maxSize);
        }
        this.maxSize = maxSize;
        this.factory = Objects.requireNonNull(factory, "factory");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.destroyer = Objects.requireNonNull(destroyer, "destroyer");
    }

    /**
     * Borrows an object, waiting up to {@code timeoutMillis}.
     *
     * @throws TimeoutException if none became available in time
     * @throws IllegalStateException if the pool is closed (before or while waiting)
     */
    public T acquire(long timeoutMillis) throws InterruptedException, TimeoutException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0");
        }
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final long start = System.nanoTime();
        while (true) {
            T candidate = null;
            boolean create = false;
            lock.lockInterruptibly();
            try {
                while (true) {
                    if (closed) {
                        throw new IllegalStateException("pool is closed");
                    }
                    if (!idle.isEmpty()) {
                        candidate = idle.pollLast();
                        borrowed.add(candidate); // reserved for us while we validate
                        break;
                    }
                    if (total < maxSize) {
                        total++;
                        create = true;
                        break;
                    }
                    remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - start);
                    if (remaining <= 0) {
                        throw new TimeoutException("no pooled object available within " + timeoutMillis + " ms");
                    }
                    slotFree.awaitNanos(remaining);
                }
            } finally {
                lock.unlock();
            }
            if (create) {
                return createReserved();
            }
            if (isValid(candidate)) {
                return candidate;
            }
            discardBorrowed(candidate);
        }
    }

    private T createReserved() {
        T created;
        try {
            created = factory.get();
            if (created == null) {
                throw new IllegalStateException("factory returned null");
            }
        } catch (Throwable t) {
            freeSlot();
            throw t;
        }
        boolean rejected;
        lock.lock();
        try {
            rejected = closed;
            if (rejected) {
                total--;
            } else {
                borrowed.add(created);
            }
        } finally {
            lock.unlock();
        }
        if (rejected) {
            destroyQuietly(created);
            throw new IllegalStateException("pool is closed");
        }
        return created;
    }

    private boolean isValid(T candidate) {
        try {
            return validator.test(candidate);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Returns a borrowed object to the pool. */
    public void release(T object) {
        Objects.requireNonNull(object, "object");
        boolean destroy;
        lock.lock();
        try {
            requireBorrowed(object);
            borrowed.remove(object);
            destroy = closed;
            if (destroy) {
                total--;
            } else {
                idle.addLast(object);
            }
            slotFree.signal();
        } finally {
            lock.unlock();
        }
        if (destroy) {
            destroyQuietly(object);
        }
    }

    /** Destroys a borrowed object instead of returning it, freeing its slot. */
    public void invalidate(T object) {
        Objects.requireNonNull(object, "object");
        lock.lock();
        try {
            requireBorrowed(object);
        } finally {
            lock.unlock();
        }
        discardBorrowed(object);
    }

    private void requireBorrowed(T object) {
        if (!borrowed.contains(object)) {
            throw new IllegalArgumentException("object is not currently borrowed from this pool");
        }
    }

    private void discardBorrowed(T object) {
        lock.lock();
        try {
            if (!borrowed.remove(object)) {
                throw new IllegalArgumentException("object is not currently borrowed from this pool");
            }
            total--;
            slotFree.signal();
        } finally {
            lock.unlock();
        }
        destroyQuietly(object);
    }

    private void freeSlot() {
        lock.lock();
        try {
            total--;
            slotFree.signal();
        } finally {
            lock.unlock();
        }
    }

    private void destroyQuietly(T object) {
        try {
            destroyer.accept(object);
        } catch (RuntimeException ignored) {
            // slot accounting is already correct; a failing destroyer must not break the pool
        }
    }

    /** Closes the pool: destroys idle objects and rejects further acquires. Idempotent. */
    @Override
    public void close() {
        ArrayDeque<T> toDestroy;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            toDestroy = new ArrayDeque<>(idle);
            total -= idle.size();
            idle.clear();
            slotFree.signalAll();
        } finally {
            lock.unlock();
        }
        toDestroy.forEach(this::destroyQuietly);
    }

    /** Idle + borrowed + objects currently being created. */
    public int size() {
        lock.lock();
        try {
            return total;
        } finally {
            lock.unlock();
        }
    }

    public int idleCount() {
        lock.lock();
        try {
            return idle.size();
        } finally {
            lock.unlock();
        }
    }

    public int borrowedCount() {
        lock.lock();
        try {
            return borrowed.size();
        } finally {
            lock.unlock();
        }
    }
}
