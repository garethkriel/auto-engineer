# Round 20261124T1057Z - Level 20 capstone: Chord DHT simulation

## Task
Package `ae.chord`, standard library only: a Chord distributed hash table simulated in-process.

- `Ring` (m-bit identifier circle, 1..40 bits): modular arithmetic, clockwise `dist`, circular intervals `inOpen` (a,b) and `inHalfOpen` (a,b] (a==b means the whole circle), `fingerStart`, and `hash(String)` = top m bits of SHA-1.
- `ChordNode`: predecessor, successor list (size r), finger table, versioned key/value store. Operations: iterative `findSuccessor` (closest preceding finger, dead entries pruned, hop budget), `stabilize`, `notify`, `checkPredecessor`, `fixNextFinger`, `maintain` (hand keys to the owner, replicate to the successor list, drop copies outside the replica set).
- `ChordNetwork`: the simulated network: `create`, `join(id, via)`, `crash(id)` (silent failure), `leave(id)` (graceful), `round()` (every live node runs the maintenance cycle), `stabilize(maxRounds)`, `put`/`get` (replicated on owner + r-1 successors, last-writer-wins by network clock), message counter and lookup results with hop count and path.
- `RingOracle`: independent model over a `TreeSet` of live ids (ideal successor/predecessor/replica set); `Invariants`: checks ring pointers, successor lists, fingers and replica placement against the oracle.

## Success criteria
- The Chord paper's m=6 example ring (nodes 1,8,14,21,32,38,42,48,51,56): finger table of node 8 is [14,14,14,21,32,42]; lookup(54) from 8 follows 8,42,51 and returns 56 in 2 hops.
- Exhaustive interval tests for m=4 against a walking oracle.
- After sequential joins the network converges (invariants clean) within a bounded number of rounds; lookups from every node for many keys equal the oracle; mean hops <= log2(n).
- Seeded churn (join / crash / leave) with at most r-1 simultaneous crashes: after each action and stabilisation the invariants hold and every key still reads its latest value.
- Mutation check: a script mutates single rules and the suite must kill most mutants.

## Assumptions
- Overlap check: benchmark j01-j35 contains no DHT/overlay routing (j17-cache, j13-ratelimit unrelated). Existing skill `consistent-hash-ring` is a static ring without finger tables, joins, failures or stabilisation; this is a different algorithm family.
- RPCs are synchronous method calls; a "message" is a counted ping / RPC. Crashed nodes are removed from the live map, calls to them throw `NodeDownException` / pings return false.
- Fault model: no more than r-1 consecutive ring neighbours crash before a stabilisation run; beyond that data loss is expected and not asserted.
