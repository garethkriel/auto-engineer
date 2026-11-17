# Task 20261117T0856Z - SingleFlight (level 10, concurrency)

Topic: CompletableFuture, thread safety, concurrent collections.
Overlap check vs `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours): none is a call-coalescing utility; SingleFlight keeps no results after completion, so it is not a cache.

## Statement
Implement `singleflight.SingleFlight<K,V>` (constructor takes an `Executor`):
- `CompletableFuture<V> run(K key, Callable<V> task)`: if no call for `key` is in flight, execute `task` on the executor; otherwise join the existing in-flight call. All joined callers get the same result.
- After a call completes (success or failure) the key is forgotten: the next `run` executes the task again. Results are never cached.
- A task failure completes every caller's future exceptionally with the original exception as cause (`ExecutionException` on `get`). The key is not poisoned.
- Cancelling one caller's future must not cancel the others.
- If the executor rejects the task, the returned future fails with that exception and the key is not left in flight.
- `null` key or task -> `NullPointerException`; `null` results are allowed.
- A continuation that calls `run` for the same key again starts a fresh execution.
- `int inFlight()` returns the number of keys currently in flight.

## Success criteria
`mvn -q -B test` passes; the concurrency test class passes in repeated runs.

## Assumptions
No timeouts. Task exceptions are passed through unwrapped as the cause.
