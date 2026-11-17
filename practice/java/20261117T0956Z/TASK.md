# Task (level 10, concurrency): MicroBatcher

Package `batch`. `MicroBatcher<T,R>` collects items submitted one at a time from many threads and runs them through one bulk function
`Function<List<T>, List<R>>`, returning a `CompletableFuture<R>` per item.

- `MicroBatcher(Function<List<T>,List<R>> bulk, int maxSize, Duration maxWait, Executor worker, Scheduler scheduler)`
- `submit(T)`: null -> NPE; closed -> IllegalStateException. The batch is dispatched to `worker` when it holds `maxSize` items, or `maxWait` after its first item arrived (whichever first).
- `flush()`: dispatch the pending batch now. `close()`: flush, then reject further submits; idempotent.
- Bulk result must have the same size and order as input; item i completes with result i. Wrong size or a thrown exception fails every future of that batch. A rejecting worker fails the batch's futures too.
- Cancelling one future does not affect its batch mates. Items keep submission order inside a batch. A stale timer never flushes a later batch.
- `Scheduler` is a tiny interface so tests can drive time manually: `Runnable schedule(Runnable task, Duration delay)` returns a cancel action; `Scheduler.of(ScheduledExecutorService)` adapts a real one.

Success: `mvn -q -B test` passes; tests are deterministic (manual scheduler, direct/queued executors) plus one real-thread stress test.

Assumptions: the bulk function runs on `worker`, never under the internal lock. Overlap check vs `benchmark/java/` (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours): none is batching/coalescing by size-or-time; j24-queue is a blocking queue, j13 a token bucket.
