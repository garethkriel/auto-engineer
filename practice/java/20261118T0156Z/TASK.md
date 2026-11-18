# Task: resilient HTTP client (level 15, networking)

Overlap check: `benchmark/java/` names include ratelimit, miniserver, cache; this task is a *client-side* retry
wrapper over `java.net.http.HttpClient` (Retry-After, idempotency rules, deadline budget). No server or limiter
algorithm is built; the in-process `HttpServer` exists only in tests.

## API (package `ae.net`)
- `record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Duration totalBudget)`; validates
  maxAttempts >= 1, base > 0, max >= base, budget >= 0. `Duration delayFor(int failedAttempts)` = base * 2^(n-1) capped at max.
- `interface Sleeper { void sleep(Duration d) throws InterruptedException; }`
- `record HttpResult(int status, String body, int attempts, Duration totalDelay)`
- `ResilientHttp(HttpClient, RetryPolicy, Sleeper)` with `HttpResult send(HttpRequest) throws IOException, InterruptedException`.

## Rules
1. Retryable status: 429, 502, 503, 504. Retryable failure: any `IOException` (incl. timeouts).
2. Only idempotent requests retry: GET, HEAD, PUT, DELETE, OPTIONS; POST/PATCH only with an `Idempotency-Key` header.
   Non-retryable requests are sent exactly once.
3. `Retry-After` as integer seconds overrides the backoff delay (capped at maxDelay). Invalid/negative/date values are ignored (backoff used).
4. If the next delay would push total slept time past `totalBudget`, stop retrying: return the last response, or throw the last IOException.
5. Attempts exhausted on a retryable status: return that last response (no exception). Exhausted on IOException: throw the last one, earlier ones added as suppressed.
6. Interrupt while sleeping: restore the interrupt flag and throw `InterruptedException`.
7. Response body is read as a String. Request must be non-null.

Success = `mvn -q -B clean test` passes; tests run against an in-process `HttpServer` on an ephemeral port.
