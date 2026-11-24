# chord-dht

**Description:** In-process Chord distributed hash table: m-bit identifier `Ring` (circular intervals, SHA-1 top-bit hashing), `ChordNode` (iterative lookup with closest-preceding-finger, stabilize/notify, successor list, round-robin finger repair, key maintenance), `ChordNetwork` (simulated RPC with message counting, join/crash/leave, maintenance rounds, replicated last-writer-wins puts/gets), plus an independent `RingOracle` (TreeSet) and an `Invariants` checker for ring pointers, fingers and replica placement.

**Language:** Java 21, standard library only.

**Tags:** chord, dht, p2p, overlay, finger-table, stabilization, replication, churn, simulation, invariant-checker

**Source round:** practice/java/20261124T1057Z

**Use it for:** overlay/ring routing work; any simulation that needs "run maintenance until an oracle-checked invariant holds" (`ChordNetwork.stabilize`); churn tests that check an oracle after every step.

**Gotchas recorded:** keep `successor()` lazily pruning dead list heads, so tests of splice behaviour (graceful leave) must assert pointers *before* any lookup runs; a key stays safe during hand-over because a copy is stored at the owner before the local copy is dropped; r adjacent crashes lose data by design (fault model r-1); the Chord paper's m=6 example (fingers of node 8 = 14,14,14,21,32,42; lookup(54) from 8 path 8,42,51 -> 56) is the literal anchor test.
