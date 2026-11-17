# Task (level 10, concurrency: thread safety, injectable time)

Implement `resilience.CircuitBreaker` (package `resilience`):

- `CircuitBreaker(int failureThreshold, long openMillis, int halfOpenSuccesses, Predicate<Throwable> countsAsFailure, Clock clock)`
  plus a convenience constructor without predicate/clock (every throwable counts, system clock).
- `Clock` is a functional interface `long nowMillis()` (injectable for tests).
- `<T> T call(Supplier<T> action)`; `void run(Runnable)`.
  - CLOSED: calls run. `failureThreshold` consecutive failures -> OPEN. A success resets the streak.
  - OPEN: calls throw `CircuitOpenException` (unchecked) without running the action, until `openMillis` elapsed;
    then the next call becomes the single HALF_OPEN probe.
  - HALF_OPEN: only one probe at a time; concurrent callers are rejected. `halfOpenSuccesses` consecutive
    successful probes -> CLOSED. A failed probe -> OPEN again with a fresh timer.
  - Throwables for which the predicate is false are neutral: rethrown, not counted, don't reset the streak, release a probe slot.
  - The action runs outside any lock. A result that arrives after the state moved on (stale call) is ignored.
- `state()`, `consecutiveFailures()`, `rejectedCount()` for observation.
- Validation: threshold >= 1, openMillis >= 1, halfOpenSuccesses >= 1, non-null args -> IllegalArgumentException/NPE.

Assumptions: time in millis from the injected clock, monotonic. Overlap check vs `benchmark/java/`: none is a circuit breaker
(j13-ratelimit is a token bucket, j17-cache a cache, j24-queue a queue).

Success: `mvn -q -B test` passes, concurrency tests stable over repeated runs.
