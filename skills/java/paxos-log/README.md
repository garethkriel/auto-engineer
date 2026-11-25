# paxos-log

- **Description:** Multi-decree Paxos replicated log in `ae.paxos` (`Node` = acceptor + proposer + learner, `Cluster` = seeded fault-injecting network with loss, duplication, delay, partitions, crash/restart and a built-in safety checker).
- **Language:** java
- **Tags:** paxos, consensus, replicated-log, ballots, recovery, simulation, deterministic-testing, distributed
- **Source round:** practice/java/20261125T1456Z

## Use it for
Any consensus exercise: reuse `Cluster` as a fault-injection harness, or `Node` as a reference for ballot/phase-1 recovery rules.

## Key rules baked in
- Quorum n/2+1; acceptor promises/accepts only ballots >= promised; promised and accepted survive crashes.
- New leader re-proposes, per undecided slot, the value with the highest accepted ballot, else NOOP; only then assigns new commands.
- Ignore Promise/Accepted whose ballot is not the current one (late acks of an old term must not count).
- Learners need an anti-entropy path (`Sync`) because the deciding leader may crash before its `Decide` arrives.
- Checker: agreement per slot, validity (decided value was submitted), promises never decrease.

Run: `mvn -q -B test` (29 tests incl. 80 chaos runs).
