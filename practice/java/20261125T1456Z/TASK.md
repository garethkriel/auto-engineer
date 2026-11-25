# Task 20261125T1456Z (level 20, topic: distributed ideas / consensus)

Multi-decree Paxos replicated log in `ae.paxos`, simulated in-process with a seeded fault-injecting network.

Overlap check: compared with all 35 `benchmark/java` directory names (j01-roman .. j35-minisql); none is a consensus task (j34-vclock is vector clocks only). Not a copy of existing skill raft-lite (different protocol: ballots, phase 1 recovery, gap filling).

## Components
- `Ballot(long round, int node)`: total order round then node; `Ballot.NONE` = (0,-1) is below every real ballot.
- `Msg` sealed: `Prepare`, `Promise`, `Nack`, `Accept`, `Accepted`, `Decide` (each has `from`, `to`).
- `Node(id, n)`: acceptor + proposer + learner. API: `submit(cmd, now)`, `tick(now)`, `receive(msg, now)`, `drainOutbox()`, `crash()`, `decided()`, `appliedLog()`, `role()`.
- `Cluster(n, seed, drop, dup, maxDelay)`: deterministic network (delay 1..maxDelay, drop and duplicate probability, blocked links, crash/restart), `step()`, `checkSafety()`.

## Rules (written before coding)
1. Quorum = n/2+1. Slots start at 0.
2. Acceptor: `Prepare(b)` with b >= promised -> promise b, reply `Promise` with all accepted (slot -> ballot,value); else `Nack(promised)`. `Accept(b,slot,v)` with b >= promised -> promised=b, accepted[slot]=(b,v), reply `Accepted`; else `Nack`. Promised and accepted survive a crash.
3. Proposer: new ballot round = max(own round, promised.round)+1. After a promise quorum it becomes leader: for every undecided slot from the first undecided one to the highest slot seen (accepted anywhere or decided) it re-proposes the value with the highest accepted ballot, or `NOOP` when none; then assigns owned commands to fresh slots. Higher-ballot Prepare/Accept/Nack makes it step down and back off (1+id ticks).
4. Leader decides a slot on an Accepted quorum for its current ballot and broadcasts `Decide`; it resends Accept every 6 ticks for undecided slots and Decide for all decided slots every 10 ticks.
5. Decided values never change; conflicting `Decide` throws `IllegalStateException("conflicting decision ...")`.
6. `appliedLog()`: the contiguous decided prefix from slot 0, NOOPs removed, a command repeated in two slots applied once.
7. Safety checker (after every step): agreement per slot across nodes, decided values were submitted or NOOP, promised ballots never decrease, decided entries never change.
8. A crash clears volatile state (role, promises, in-flight, owned commands); promised, ownRound, accepted, decided are durable.

## Success criteria
- `mvn -q -B test` passes: unit tests for acceptor/proposer rules, scripted recovery scenarios, chaos runs over many seeds with n=3,5 plus partitions and crashes, liveness after healing, mutation pass killing each safety rule.
