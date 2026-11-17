package bench.queue;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class BoundedQueue<E> {
    private final int capacity;
    private final ArrayDeque<E> items = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private boolean closed;

    public BoundedQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null || unit == null) throw new IllegalArgumentException("null");
        long nanos = Math.max(0L, unit.toNanos(timeout));
        lock.lockInterruptibly();
        try {
            while (true) {
                if (closed) throw new IllegalStateException("closed");
                if (items.size() < capacity) {
                    items.addLast(e);
                    notEmpty.signal();
                    return true;
                }
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
        } finally {
            lock.unlock();
        }
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) throw new IllegalArgumentException("null unit");
        long nanos = Math.max(0L, unit.toNanos(timeout));
        lock.lockInterruptibly();
        try {
            while (true) {
                if (!items.isEmpty()) {
                    E e = items.removeFirst();
                    notFull.signal();
                    return e;
                }
                if (closed || nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
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
