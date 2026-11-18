# Task: Raft-lite leader election and log replication over a simulated network (level 19)

Overlap check: `benchmark/java/` has j34-vclock (vector clocks) but nothing on leader election or log replication, so this task does not copy any benchmark core algorithm.

## Statement
Package `ae.raft`, standard library only, single-threaded and tick-driven (no real clocks or threads).

* `LogEntry(int term, String command)`; command `""` is the leader no-op (`LogEntry.NOOP`).
* `Message` sealed interface: `RequestVote`, `VoteReply`, `AppendEntries`, `AppendReply`; each carries its sender's `term()`.
* `RaftNode(id, clusterSize, Transport, Random, minElectionTicks, maxElectionTicks, heartbeatTicks)`:
  `tick()`, `receive(from, msg)`, `propose(cmd)` (leader only, returns index or -1), `restart()` (crash recovery: keeps term/vote/log, loses volatile state), accessors `term() role() votedFor() log() commitIndex() committed()`.
* `SimCluster(n, seed, lossProb, dupProb, minDelay, maxDelay)`: deterministic network with delay, loss, duplication, per-link partitions and node crash/restart; `tick()`; built-in safety checker `violations()`.

## Design
* Raft per the paper: terms, one vote per term, up-to-date log check, prevIndex/prevTerm consistency check, truncate on conflict only, commit by majority only for entries of the current term (a leader appends a no-op on election).
* Network: in-flight queue ordered by (deliverAt, seq); drop at send time if source down/link blocked/loss; drop at delivery if destination down or link blocked. Network RNG is separate from node RNGs, all derived from the seed.
* Checker: at most one leader per term ever seen; globally committed log agrees across nodes at every index (state machine safety).

## Success criteria
* Unit tests of vote rules, append consistency, conflict truncation, commit rule (never commit an old-term entry by counting).
* Cluster tests: election in 3/5 nodes, leader crash, minority partition cannot commit and its entries are overwritten after heal, lossy network converges, same seed gives identical trace.
* Randomised chaos over many seeds (crashes, partitions, heals, proposals): zero violations, and after healing everything converges to identical logs containing every command ever committed.
* `mvn -q -B clean test` passes.
