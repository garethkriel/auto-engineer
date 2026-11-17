# micro-batcher

Description: `batch.MicroBatcher<T,R>` turns single `submit(item)` calls from many threads into batched calls of one bulk function, dispatched when the batch is full or `maxWait` after its first item. Per-item `CompletableFuture`; batch-wide failure on exception, wrong result size or rejected worker; `flush()`/`close()`. Time is injectable through the `Scheduler` interface (`Scheduler.of(ScheduledExecutorService)` for production), so tests use a manual clock.

Language: java
Tags: concurrency, batching, CompletableFuture, scheduler, generation-counter, testability
Source round: practice/java/20261117T0956Z

Key idea: detach the batch under the lock (bump a generation, cancel the timer), run the bulk function outside it on the worker; a timer carries its generation and is ignored if stale.
