# retry-backoff

- **Description:** Retry a `Callable` with exponential backoff. Overflow-safe capped delay computation, injectable `Sleeper` so tests never sleep, checked `RetryExhaustedException` carrying attempts, total delay and last cause, interrupt-safe, non-retryable errors propagate at once.
- **Language:** java
- **Tags:** retry, backoff, java.time.Duration, checked-exception, Optional, injectable-dependency
- **Source round:** practice 20261114T0156Z (level 4)
- **Test:** `mvn -q -B test` (19 tests)
