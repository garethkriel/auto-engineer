package bench.queue;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** A closeable, bounded, FIFO blocking queue. */
public final class BoundedQueue<E> {
    private final int capacity;
    private final ArrayDeque<E> items;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private boolean closed;

    public BoundedQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.items = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) {
            throw new IllegalArgumentException("null element");
        }
        if (unit == null) {
            throw new IllegalArgumentException("null unit");
        }
        long nanos = timeout <= 0 ? 0 : unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("queue is closed");
                }
                if (items.size() < capacity) {
                    items.addLast(e);
                    notEmpty.signal();
                    return true;
                }
                if (nanos <= 0) {
                    return false;
                }
                try {
                    nanos = notFull.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    if (!closed && items.size() < capacity) {
                        notFull.signal();
                    }
                    throw ie;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new IllegalArgumentException("null unit");
        }
        long nanos = timeout <= 0 ? 0 : unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (true) {
                if (!items.isEmpty()) {
                    E x = items.pollFirst();
                    notFull.signal();
                    return x;
                }
                if (closed || nanos <= 0) {
                    return null;
                }
                try {
                    nanos = notEmpty.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    if (!items.isEmpty()) {
                        notEmpty.signal();
                    }
                    throw ie;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return items.size();
        } finally {
            lock.unlock();
        }
    }
}
