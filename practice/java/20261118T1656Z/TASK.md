# Task: consistent-hash ring with virtual nodes, replicas and bounded load (level 19)

Topic: distributed ideas simulated in-process - consistent hashing.
Overlap check: `benchmark/java/` has j34-vclock (vector clocks), j13-ratelimit, j17-cache, j24-queue; none is hashing/placement, so no overlap.

## Package `ae.ring`
- `Hasher` (functional interface `long hash(String)`) plus `Hasher.DEFAULT`, a deterministic 64-bit hash (FNV-1a + splitmix finaliser).
- `HashRing`: immutable ring. `HashRing.empty()` / `empty(Hasher, int vnodesPerWeight)`; `withNode(id, weight)`, `withoutNode(id)` return new rings.
  A node of weight w owns `w * vnodesPerWeight` points at `hash(id + "#" + i)`.
  - `owner(key)`: `Optional<String>`, the node of the first point clockwise from `hash(key)` (point equal to the key hash counts; wraps around). Empty ring -> empty.
  - `preferenceList(key, n)`: first n DISTINCT nodes clockwise from the key (fewer if the ring has fewer); first element equals owner.
  - `share()`: `Map<String, Double>` fraction of the 2^64 hash space each node owns; sums to 1.
  - `assignBounded(keys, c)`: bounded-load assignment; per-node capacity `ceil(c * keys / nodes)`, `c >= 1`; each key goes to the first node in its preference order with spare capacity.
- `Rebalance.moves(oldRing, newRing, keys)`: list of `Move(key, from, to)` for keys whose owner changed.

## Decisions / assumptions
- Unsigned 64-bit ordering: hashes are XOR-ed with `Long.MIN_VALUE` so signed order equals unsigned order.
- Two points at the same position: the lexicographically smaller node id keeps it (deterministic, order independent).
- Weight and vnode count must be >= 1; node ids non-empty; duplicate `withNode` replaces the weight.

## Success criteria
`mvn -q -B clean test` passes: exact-position tests with a table hasher, minimal-movement properties, balance and weight proportionality, replica distinctness, bounded-load invariants, order independence.
