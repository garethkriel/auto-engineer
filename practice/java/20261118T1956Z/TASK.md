# Practice 20261118T1956Z — level 20 capstone: persistent job scheduler with retries

## Understand
Build `ae.jobs`: a small end-to-end durable job scheduler (standard library only, Java 21).
Overlap check: `benchmark/java/` has cron, queue, topo, minisql, ratelimit and others. This task is a
write-ahead-journal job runner with dependency DAG, retries/backoff and crash recovery; it is not a cron
parser, bounded queue, topological sort or SQL engine. Core algorithm differs from all names listed.

## Design
- `TimeSource` (millis) injected, `ManualClock` in tests; no real sleeping.
- `Journal` port (`append`, `readAll`) with `InMemoryJournal` and `FileJournal` (fsync per append,
  torn-tail trimming on open). Each line is `crc32hex SPACE body`; body is tab-separated, percent-encoded fields.
- Events (sealed): Submitted, Started, Succeeded, Failed(retry at), Died, Cancelled.
  `commit(e)` = append to journal first, then `apply(e)` (a pure fold over in-memory state). Recovery = replay.
- `JobScheduler`: `submit(JobSpec)`, `tick()` runs every due ready job (PENDING, runAt <= now, all dependencies
  SUCCEEDED) ordered by (runAt, submission order); handlers run outside the lock. Retries use
  `RetryPolicy(maxAttempts, baseDelayMs, maxDelayMs)`, delay after attempt n = min(max, base*2^(n-1)).
  `PermanentFailure` skips retries. A job that dies cancels its transitive dependents (`settleBlocked`,
  re-derived on recovery so a crash between Died and Cancelled is repaired).
- Crash semantics: a Started with no outcome is recovered as a failed attempt ("crashed during attempt n"),
  i.e. at-least-once execution.
- Corruption: a torn last line (no newline) is trimmed; any bad CRC or impossible transition is `CorruptJournalException`.

## Success criteria
- `mvn -q -B clean test` passes: literal backoff schedule, dependency/cascade tests, recovery tests,
  crash-at-every-append sweep, torn tail and flip-every-byte tests, seeded random DAG oracle test, concurrency test.
