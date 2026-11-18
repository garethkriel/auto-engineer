# saga-coordinator

- **Description:** Crash-resumable saga coordinator in four packages (domain, port, adapter, app). Steps with compensation, run forward in order, undone in reverse on failure; all state derived from a write-ahead `SagaLog`, so re-running the same saga id resumes after a crash. Retry limits, STUCK outcome when compensation keeps failing, terminal-log short-circuit.
- **Language:** java
- **Tags:** saga, compensation, write-ahead-log, ports-and-adapters, idempotency, crash-recovery
- **Source round:** practice/java/20261118T1056Z
- **Test idea worth reusing:** a log decorator that throws at the k-th append, looped over every k, with a ledger oracle (execute adds, compensate removes).
