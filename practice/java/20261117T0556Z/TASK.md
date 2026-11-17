# Task (level 10, concurrency): bounded parallel helpers

Package `conc`, class `Parallel` (static methods, uses `Executor`/`CompletableFuture`):

- `mapOrdered(Executor, List<? extends T>, Function<? super T,? extends R>, int maxConcurrency)`: runs the function over the items, at most `maxConcurrency` at once, and returns results in input order. Fail-fast: after the first failure no new item starts; the call returns promptly (without waiting for slow running items) by throwing `ParallelException` whose cause is the first failure in time. Interrupting the caller restores the interrupt flag and throws `ParallelException`. Null args / `maxConcurrency < 1` -> `IllegalArgumentException`/NPE.
- `firstSuccess(Executor, List<? extends Supplier<? extends T>>)`: future completed by the first supplier that succeeds; failures are skipped; if all fail, completes exceptionally with `AllFailedException` holding every cause. Suppliers not yet started when a winner exists are skipped. Empty list -> `IllegalArgumentException`.
- `withTimeout(CompletableFuture<T>, Duration, ScheduledExecutorService)`: new future mirroring the source, or failing with `TimeoutException` after the duration; the timer is cancelled when the source completes first.

Assumptions: running tasks cannot be cancelled (plain `Executor`); only unstarted work is skipped.
Overlap check vs `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours): none is a parallel executor/futures utility, so allowed.

Success: `mvn -q -B test` passes.
