# Task: Retry policy with exponential backoff (Java, level 4)

Topics: custom checked exception, `Optional`, `java.time.Duration`, exceptions.

Overlap check: `benchmark/java/` has roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex. A retry loop with backoff shares no core algorithm with any of them.

## Requirements (package `retry`)
- `RetryPolicy` record `(int maxAttempts, Duration initialDelay, int multiplier, Duration maxDelay)`; compact constructor validates (maxAttempts>=1, initialDelay non-negative, multiplier>=1, maxDelay>=initialDelay, nulls -> NPE, others IAE).
- `RetryPolicy.delayBefore(int attempt)`: delay to wait before retry number `attempt` (1 = first retry): `initialDelay * multiplier^(attempt-1)` capped at `maxDelay`, overflow-safe for huge attempts. attempt<1 -> IAE.
- `Sleeper` functional interface: `void sleep(Duration) throws InterruptedException`.
- `RetryExhaustedException` (checked): carries `attempts()`, `totalDelay()`, cause = last failure.
- `Retrier(RetryPolicy, Sleeper)` with `<T> T call(Callable<T>, Predicate<Exception> retryable) throws RetryExhaustedException, Exception`:
  - returns first success; non-retryable exception propagates immediately without sleeping;
  - retryable failures sleep `delayBefore(n)` then retry, up to maxAttempts total calls; then throws RetryExhaustedException;
  - interrupt during sleep: restore the interrupt flag and throw the InterruptedException.
- `Retrier.attempt(Callable<T>, Predicate<Exception>)` returns `Optional<T>` (empty when exhausted; non-retryable still propagates). `lastReport()` is not required.

## Success
`mvn -q -B test` passes.
