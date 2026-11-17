# parallel-helpers

- **Description:** `conc.Parallel`: `mapOrdered` (bounded concurrency, input order, fail-fast, interrupt-safe), `firstSuccess` (first success wins, `AllFailedException` otherwise), `withTimeout` (cancellable timer over a `CompletableFuture`).
- **Language:** java (21)
- **Tags:** concurrency, ExecutorService, CompletableFuture, Semaphore, fail-fast, timeout
- **Source round:** practice/java/20261117T0556Z
- **Notes:** running tasks are never interrupted (plain `Executor`). Test with repeated runs; see the lesson entry on racy "who wins" tests.
