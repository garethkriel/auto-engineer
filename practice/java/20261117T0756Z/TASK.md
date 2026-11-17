# Task (level 10, concurrency: thread safety, executors)

Implement `keyed.KeyedExecutor`: runs tasks submitted with a key **serially, in submission order per key**, while tasks with different keys run in parallel on a caller-supplied `java.util.concurrent.Executor`.

API:
- `KeyedExecutor(Executor delegate)`
- `<T> CompletableFuture<T> submit(Object key, Callable<T> task)`: returns a future; task exceptions complete it exceptionally and never stop later tasks of that key; a future cancelled before its turn is skipped. Throws `RejectedExecutionException` after `shutdown()` or if the delegate rejects (state rolled back, nothing left queued).
- `int activeKeys()`: keys with queued or running tasks (a key is removed when its queue drains, so no leak).
- `void shutdown()`: refuse new tasks; already accepted ones still run.
- `boolean awaitIdle(Duration timeout)`: true when no key is active within the timeout.
- Null arguments -> `NullPointerException`.

Success: `mvn -q -B test` passes, including a stress test (8 keys x 200 tasks) with a non-atomic per-key counter, run repeatedly.

Assumptions: after each task the next task of the key is re-submitted to the delegate (fairness: a hot key cannot monopolise a worker). Overlap check vs `benchmark/java/` (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours): none is a per-key serial executor; closest is queue/ratelimit/cache, which are data structures, not execution ordering. Allowed.
