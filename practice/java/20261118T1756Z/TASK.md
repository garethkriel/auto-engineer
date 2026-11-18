# Task: state-based CRDT library with convergence simulation (level 19)

Topic: distributed ideas simulated in-process - CRDTs (conflict-free replicated data types).
Overlap check: `benchmark/java/` has j34-vclock (vector clocks); this task uses per-replica counters and unique add-tags, not vector-clock comparison, and covers merge-law and gossip convergence testing. Not a paraphrase.

## Package `ae.crdt` (all types immutable; every mutator returns a new value)
- `GCounter`: `empty()`, `increment(replica, n)` (n >= 0), `value()` (long), `merge(other)` = pointwise max per replica.
- `PNCounter`: two GCounters; `increment/decrement(replica, n)`, `value()` (may be negative), `merge`.
- `LwwRegister<T>`: `empty()`, `set(value, time, replica)`, `get()` (Optional<T>), `merge`. Winner = greatest (time, replica id); a full stamp tie is broken by `String.valueOf(value)` so merge stays commutative.
- `OrSet<E>` (observed-remove, add wins): `empty()`, `add(e, replica)` mints a unique tag (replica, counter); `remove(e)` tombstones only the tags this replica has observed; `contains`, `elements()` (Set), `merge` = union of entries and tombstones, max of clocks.

## Success criteria
`mvn -q -B clean test`: exact scenario tests (concurrent add/remove, re-add after remove, LWW ties), merge laws (commutative, associative, idempotent) on random states, monotonic counters, and a seeded gossip simulation where replicas applying random ops and exchanging random merges converge to equal states equal to an oracle.

## Assumptions
- Replica ids non-empty; counters are `long` and use `Math.addExact`.
- `merge` never mutates; values compare by `equals`.
