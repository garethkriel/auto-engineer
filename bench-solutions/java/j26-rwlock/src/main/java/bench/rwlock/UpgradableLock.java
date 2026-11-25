package bench.rwlock;

import java.util.HashSet;
import java.util.Set;

/**
 * Non-reentrant read-write lock with writer preference and an upgrade slot.
 * All state is guarded by the monitor of this object; every state change that
 * may enable a waiter calls {@code notifyAll}.
 */
public final class UpgradableLock {
    private final Set<Thread> readers = new HashSet<>();
    private Thread writer;
    private int waitingWriters;
    private Thread upgrader;

    public UpgradableLock() {
    }

    private boolean holds(Thread t) {
        return writer == t || readers.contains(t);
    }

    public synchronized void readLock() throws InterruptedException {
        Thread me = Thread.currentThread();
        if (holds(me)) {
            throw new IllegalStateException("lock already held by this thread");
        }
        try {
            while (writer != null || waitingWriters > 0 || upgrader != null) {
                wait();
            }
        } catch (InterruptedException e) {
            notifyAll();
            throw e;
        }
        readers.add(me);
    }

    public synchronized void readUnlock() {
        if (!readers.remove(Thread.currentThread())) {
            throw new IllegalMonitorStateException("read lock not held");
        }
        notifyAll();
    }

    public synchronized void writeLock() throws InterruptedException {
        Thread me = Thread.currentThread();
        if (holds(me)) {
            throw new IllegalStateException("lock already held by this thread");
        }
        waitingWriters++;
        try {
            while (writer != null || !readers.isEmpty() || upgrader != null) {
                wait();
            }
        } catch (InterruptedException e) {
            waitingWriters--;
            notifyAll();
            throw e;
        }
        waitingWriters--;
        writer = me;
    }

    public synchronized void writeUnlock() {
        if (writer != Thread.currentThread()) {
            throw new IllegalMonitorStateException("write lock not held");
        }
        writer = null;
        notifyAll();
    }

    public synchronized boolean tryUpgrade() throws InterruptedException {
        Thread me = Thread.currentThread();
        if (!readers.contains(me)) {
            throw new IllegalMonitorStateException("read lock not held");
        }
        if (upgrader != null) {
            return false;
        }
        upgrader = me;
        try {
            while (readers.size() > 1) {
                wait();
            }
        } catch (InterruptedException e) {
            upgrader = null;
            notifyAll();
            throw e;
        }
        readers.remove(me);
        writer = me;
        upgrader = null;
        notifyAll();
        return true;
    }

    public synchronized void downgrade() {
        Thread me = Thread.currentThread();
        if (writer != me) {
            throw new IllegalMonitorStateException("write lock not held");
        }
        writer = null;
        readers.add(me);
        notifyAll();
    }

    public synchronized int readers() {
        return readers.size();
    }

    public synchronized boolean isWriteLocked() {
        return writer != null;
    }
}
