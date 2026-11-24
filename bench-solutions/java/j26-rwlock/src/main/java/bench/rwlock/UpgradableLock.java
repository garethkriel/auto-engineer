package bench.rwlock;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Non-reentrant read-write lock with writer preference and an exclusive upgrade slot. */
public final class UpgradableLock {
    private final ReentrantLock mon = new ReentrantLock();
    private final Condition changed = mon.newCondition();
    private final Set<Thread> readers = new HashSet<>();
    private Thread writer;
    private int waitingWriters;
    private Thread upgrader;

    public UpgradableLock() { }

    private boolean holdsAny(Thread t) {
        return writer == t || readers.contains(t);
    }

    public void readLock() throws InterruptedException {
        Thread me = Thread.currentThread();
        mon.lock();
        try {
            if (holdsAny(me)) {
                throw new IllegalStateException("lock already held by this thread");
            }
            while (writer != null || waitingWriters > 0 || upgrader != null) {
                changed.await();
            }
            readers.add(me);
        } finally {
            mon.unlock();
        }
    }

    public void readUnlock() {
        Thread me = Thread.currentThread();
        mon.lock();
        try {
            if (!readers.remove(me)) {
                throw new IllegalMonitorStateException("read lock not held");
            }
            changed.signalAll();
        } finally {
            mon.unlock();
        }
    }

    public void writeLock() throws InterruptedException {
        Thread me = Thread.currentThread();
        mon.lock();
        try {
            if (holdsAny(me)) {
                throw new IllegalStateException("lock already held by this thread");
            }
            waitingWriters++;
            try {
                while (writer != null || !readers.isEmpty() || upgrader != null) {
                    changed.await();
                }
            } catch (InterruptedException e) {
                waitingWriters--;
                changed.signalAll();
                throw e;
            }
            waitingWriters--;
            writer = me;
        } finally {
            mon.unlock();
        }
    }

    public void writeUnlock() {
        mon.lock();
        try {
            if (writer != Thread.currentThread()) {
                throw new IllegalMonitorStateException("write lock not held");
            }
            writer = null;
            changed.signalAll();
        } finally {
            mon.unlock();
        }
    }

    public boolean tryUpgrade() throws InterruptedException {
        Thread me = Thread.currentThread();
        mon.lock();
        try {
            if (!readers.contains(me)) {
                throw new IllegalMonitorStateException("read lock not held");
            }
            if (upgrader != null) {
                return false;
            }
            upgrader = me;
            try {
                while (readers.size() > 1) {
                    changed.await();
                }
            } catch (InterruptedException e) {
                upgrader = null;
                changed.signalAll();
                throw e;
            }
            readers.remove(me);
            upgrader = null;
            writer = me;
            return true;
        } finally {
            mon.unlock();
        }
    }

    public void downgrade() {
        Thread me = Thread.currentThread();
        mon.lock();
        try {
            if (writer != me) {
                throw new IllegalMonitorStateException("write lock not held");
            }
            writer = null;
            readers.add(me);
            changed.signalAll();
        } finally {
            mon.unlock();
        }
    }

    public int readers() {
        mon.lock();
        try {
            return readers.size();
        } finally {
            mon.unlock();
        }
    }

    public boolean isWriteLocked() {
        mon.lock();
        try {
            return writer != null;
        } finally {
            mon.unlock();
        }
    }
}
