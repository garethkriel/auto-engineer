# circuit-breaker

- **Description:** Thread-safe circuit breaker (CLOSED / OPEN / HALF_OPEN) with injectable `Clock`, a single concurrent half-open probe, N-success close threshold, neutral (non-counting) throwables and generation tags that make late results from older states harmless. Action runs outside the lock.
- **Language:** java
- **Tags:** concurrency, resilience, state-machine, injectable-time, generation-counter
- **Source round:** practice/java/20261117T1056Z

Usage: `new CircuitBreaker(5, 30_000, 2).call(() -> remote())`; catch `CircuitOpenException` for rejected calls.
Tests: `mvn -q -B test` (17 tests, stable over 6 repeated runs).
