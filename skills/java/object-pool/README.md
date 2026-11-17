# object-pool

- **Description:** `pool.ObjectPool<T>`, a bounded blocking resource pool: lazy creation, LIFO reuse, acquire with timeout, validator on reuse, `invalidate`, idempotent `close`, identity-checked `release`. Callbacks run outside the lock; a slot is reserved before the factory runs and returned if it fails.
- **Language:** java
- **Tags:** concurrency, ReentrantLock, Condition, resource pool, timeout, thread safety
- **Source round:** practice/java/20261117T1156Z
- **Verified:** `mvn -q -B test` passes (16 tests, run 7 times).

Reusable ideas: reserve-then-create accounting, identity set of borrowed objects, awaitNanos loop with recomputed remaining time.
