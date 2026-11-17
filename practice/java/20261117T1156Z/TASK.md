# Task (level 10, concurrency: bounded blocking resource pool)

Implement `pool.ObjectPool<T>` (package `pool`, implements `AutoCloseable`):

- `ObjectPool(int maxSize, Supplier<T> factory, Predicate<T> validator, Consumer<T> destroyer)`; maxSize >= 1, non-null args
  (IllegalArgumentException / NullPointerException). Convenience ctor `(maxSize, factory)` = always valid, no-op destroyer.
- `T acquire(long timeoutMillis) throws InterruptedException, TimeoutException`: reuse an idle object (most recently released first),
  else create one lazily if fewer than maxSize exist, else block up to the timeout (timeout 0 = no waiting).
  Idle objects failing `validator` are destroyed and skipped. Validator, factory and destroyer run outside the lock.
- `void release(T)`: return a borrowed object; wakes one waiter. Releasing something not currently borrowed (foreign or twice) -> IllegalArgumentException (identity based).
- `void invalidate(T)`: destroy a borrowed object and free its slot.
- Factory throwing (or returning null -> IllegalStateException) frees the reserved slot and propagates; the pool stays usable.
- `close()`: idempotent; destroys idle objects; waiting/new `acquire` calls throw IllegalStateException; objects released after close are destroyed.
- Observation: `size()` (idle+borrowed+being created), `idleCount()`, `borrowedCount()`.

Assumptions: timeouts are real time (System.nanoTime); destroyer exceptions are swallowed so slot accounting stays correct.
Overlap check vs `benchmark/java/` (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit,
json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours): none is a resource pool.
