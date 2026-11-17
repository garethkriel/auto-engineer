# single-flight

- **Description:** `SingleFlight<K,V>` coalesces concurrent calls for the same key into one execution on a supplied Executor. Callers get independent `CompletableFuture`s (cancel-safe); failures propagate with the original cause; keys are forgotten on completion (no caching); executor rejection leaves no key in flight.
- **Language:** java
- **Tags:** concurrency, CompletableFuture, deduplication, thread-safety
- **Source round:** practice/java/20261117T0856Z
