# job-scheduler

- **Description:** durable single-runner job scheduler (`ae.jobs`). Jobs have a type, payload, dependencies and earliest run time; failures retry with exponential backoff (`RetryPolicy`) or die immediately (`PermanentFailure`); a dead job cancels its transitive dependents. Every state change is an event appended to a `Journal` (in-memory or fsync'd, checksummed `FileJournal`) before it is applied, so recovery = replay + closing attempts left RUNNING as failed attempts (at-least-once).
- **Language:** java
- **Tags:** scheduler, retries, backoff, write-ahead-log, crash-recovery, dependency-dag, event-fold, capstone
- **Source round:** practice/java/20261118T1956Z
- **Tests:** `mvn -q -B test` (33 tests): literal backoff times, cascade cancel, crash at every append index and double crashes, truncate/flip-every-byte journal checks, seeded random-DAG oracle.

Reuse notes: handlers run outside the lock; `settleBlocked` is an idempotent fixed point re-run on recovery; each crash consumes an attempt, so repeated crashes kill a poison job.
