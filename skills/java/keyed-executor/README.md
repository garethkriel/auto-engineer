# keyed-executor

- **Description:** `KeyedExecutor` runs tasks of the same key serially in submission order and different keys in parallel on any `Executor`. Failures and cancellations of one task don't affect later ones; keys are removed when drained; `shutdown()` and `awaitIdle(Duration)` are supported; delegate rejection is rolled back; each key turn re-submits itself so a hot key can't hog a worker.
- **Language:** java
- **Tags:** concurrency, executor, per-key ordering, CompletableFuture, thread safety
- **Source round:** practice/java/20261117T0756Z
- **Tests:** 11 JUnit 5 tests incl. a stress test with plain (non-atomic) per-key counters; `mvn -q -B test`.
