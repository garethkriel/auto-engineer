package bench.rwlock;

import java.util.HashSet;
import java.util.Set;

/** Non-reentrant read-write lock with writer preference and a single upgrade slot. */
public final class UpgradableLock {
    private final Set<Thread> readers = new HashSet<>();
    private Thread writer;
    private int waitingWriters;
    private Thread upgrader;

    public UpgradableLock() { }

    private boolean holdsAny(Thread t) {
        return writer == t || readers.contains(t);
    }

    public synchronized void readLock() throws InterruptedException {
        Thread me = Thread.currentThread();
        if (holdsAny(me)) {
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
        Thread me = Thread.currentThread();
        if (!readers.remove(me)) {
            throw new IllegalMonitorStateException("read lock not held");
        }
        notifyAll();
    }

    public synchronized void writeLock() throws InterruptedException {
        Thread me = Thread.currentThread();
        if (holdsAny(me)) {
            throw new IllegalStateException("lock already held by this thread");
        }
        waitingWriters++;
        try {
            while (writer != null || !readers.isEmpty() || upgrader != null) {
                wait();
            }
        } finally {
            waitingWriters--;
            notifyAll();
        }
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
        upgrader = null;
        writer = me;
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
