# raft-lite

- **Description:** Tick-driven Raft (leader election + log replication, no snapshots/membership) in `ae.raft`, plus `SimCluster`: a seeded deterministic simulated network (delay, loss, duplication, partitions, crash/restart) with a built-in safety checker.
- **Language:** java
- **Tags:** raft, consensus, leader-election, log-replication, simulation, deterministic-testing, distributed
- **Source round:** practice/java/20261118T1556Z

## Use it for
Any in-process distributed-protocol exercise: reuse `SimCluster` as a fault-injection harness, or `RaftNode` as a reference for term/vote/log rules.

## Key rules baked in
- Step down on any higher term; one vote per term; vote only if candidate log is at least as up to date.
- Truncate only on a real conflict (a delayed shorter AppendEntries must not delete entries).
- Commit by counting only entries of the current term (leader appends a no-op); matchIndex only moves up, nextIndex stays above matchIndex.
- Checker: at most one leader per term; globally committed log agrees at every index.

Run: `mvn -q -B test` (29 tests, incl. 60-seed chaos run).
