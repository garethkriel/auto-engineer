package bench.rwlock;

import java.util.HashSet;
import java.util.Set;

/**
 * A non-reentrant read-write lock with writer preference, a single-slot
 * read-to-write upgrade and an atomic write-to-read downgrade.
 * All state is guarded by the intrinsic monitor of this object.
 */
public final class UpgradableLock {
    private final Set<Thread> readers = new HashSet<>();
    private Thread writer;
    private int waitingWriters;
    private Thread upgrader;

    public UpgradableLock() {
    }

    private boolean holdsAny(Thread t) {
        return writer == t || readers.contains(t);
    }

    public synchronized void readLock() throws InterruptedException {
        Thread me = Thread.currentThread();
        if (holdsAny(me)) {
            throw new IllegalStateException("lock already held by this thread");
        }
        while (writer != null || waitingWriters > 0 || upgrader != null) {
            wait();
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
            writer = me;
        } finally {
            waitingWriters--;
            // readers blocked only by this waiting writer may now proceed
            notifyAll();
        }
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
            readers.remove(me);
            writer = me;
            return true;
        } finally {
            upgrader = null;
            notifyAll();
        }
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
