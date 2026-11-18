# resilient-http

- **Description:** `ae.net.ResilientHttp` wraps `java.net.http.HttpClient` with safe retries: only idempotent methods (or POST/PATCH with `Idempotency-Key`), retry on 429/502/503/504 and any `IOException` (including timeouts), exponential backoff (`RetryPolicy`), integer-seconds `Retry-After` (capped), a total-delay budget, flat suppressed-exception reporting and interrupt-safe sleeping via an injectable `Sleeper`.
- **Language:** java
- **Tags:** networking, http, retry, backoff, retry-after, idempotency, httpserver-test
- **Source round:** practice/java/20261118T0156Z
- **Tests:** 19 JUnit tests against an in-process `com.sun.net.httpserver.HttpServer` on port 0 (`mvn -q -B test`).
