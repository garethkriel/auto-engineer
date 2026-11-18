# Task (level 17, larger design): crash-resumable saga coordinator

Overlap check: benchmark has j09-bank, j13-ratelimit, j19-allocate, j24-queue, j32-fsm, j33-pricing, j34-vclock
etc.; none is a saga/compensation coordinator with a write-ahead log. Recent rounds covered a transactional outbox.

## Design (four packages under `ae.saga`)
- `domain`: `SagaStatus {COMPLETED, COMPENSATED, STUCK}`, `Outcome(status, failedStep, reason)`,
  `LogEntry(kind, step)` with kinds BEGIN, DONE, FAILED, COMPENSATED, COMPLETE, ABORTED.
- `port`: `SagaStep` (`name()`, `execute(ctx)`, `compensate(ctx)`; may throw `StepException`, with a `retryable` flag),
  `SagaLog` (`append(sagaId, entry)`, `entries(sagaId)`; append is the write-ahead point).
- `adapter`: `InMemorySagaLog`.
- `app`: `SagaCoordinator(log, maxAttempts)` with `Outcome run(sagaId, List<SagaStep> steps, Map<String,String> ctx)`.

## Rules
1. State is derived only from the log, so calling `run` again with the same id resumes after a crash.
2. Forward phase: steps execute in order; a step already DONE in the log is skipped. After success append DONE.
   A retryable failure is retried up to `maxAttempts` total; a non-retryable one (or exhausted retries) appends
   FAILED(step) and switches to compensation. When all steps are done append COMPLETE -> COMPLETED.
3. Compensation: for steps DONE and not yet COMPENSATED, in reverse order, call `compensate` (retried up to
   `maxAttempts`); append COMPENSATED(step) after each. If one never succeeds, return STUCK without a terminal entry
   (a later `run` retries). When all are undone append ABORTED -> COMPENSATED. The failed step itself is not compensated.
4. A terminal log (COMPLETE/ABORTED) makes `run` return the recorded outcome without touching any step.
5. Steps are at-least-once, so they must be idempotent; crashes (log append throwing) between a step and its DONE entry rerun it.
6. Validation: null/empty args and duplicate step names -> IllegalArgumentException.

## Success criteria
`mvn -q -B clean test` passes: literal cases for each rule plus an exhaustive crash-at-every-append test using a ledger oracle.
