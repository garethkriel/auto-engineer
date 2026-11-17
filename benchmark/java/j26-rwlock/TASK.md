# j26-rwlock: Upgradable read-write lock

Java 21, standard library only. Put your solution under `bench-solutions/java/j26-rwlock/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.rwlock;
public final class UpgradableLock {
    public UpgradableLock();
    public void readLock() throws InterruptedException;
    public void readUnlock();
    public void writeLock() throws InterruptedException;
    public void writeUnlock();
    public boolean tryUpgrade() throws InterruptedException;
    public void downgrade();
    public int readers();
    public boolean isWriteLocked();
}
```

- **Holders:** locks belong to threads. At any moment a thread holds nothing, one read lock, or the write lock. Many threads may hold read locks at once. The write lock excludes every other holder, reader or writer.
- **Not reentrant:** calling `readLock` or `writeLock` from a thread that already holds a lock throws `IllegalStateException` and changes nothing.
- **Writer preference:** while any thread is waiting in `writeLock`, a new `readLock` call waits too, even if only readers hold the lock. Threads that already hold read locks keep them.
- **`tryUpgrade()`:** the caller must hold a read lock.
  - If another thread is already waiting in `tryUpgrade`, it returns `false` at once and the caller keeps its read lock.
  - Otherwise it waits until the caller is the only reader, then turns the caller's read lock into the write lock and returns `true`. No other thread may take the write lock first, not even one that was already waiting in `writeLock`.
  - While an upgrade is waiting, new `readLock` calls wait, as they would for a waiting writer.
- **`downgrade()`:** the caller must hold the write lock. It turns it into a read lock atomically, so no writer can get in between. Waiting readers may then proceed, unless a writer is waiting (writer preference still applies).
- **Wake-ups:** a waiting thread must acquire promptly once the rules allow it. No lost wake-ups and no deadlock, including under heavy concurrent use of every method.
- **Interrupts:** a thread interrupted while waiting in `readLock`, `writeLock` or `tryUpgrade` gets `InterruptedException`. It gains nothing: after an interrupted `tryUpgrade` it still holds its read lock, and the upgrade slot is free again. Other waiters must not be left stuck because of the interrupted thread.
- **Misuse:** calling `readUnlock`, `writeUnlock`, `tryUpgrade` or `downgrade` from a thread that doesn't hold the lock that method needs throws `IllegalMonitorStateException`.
- **`readers()`** is the number of threads holding a read lock. **`isWriteLocked()`** is whether some thread holds the write lock.
