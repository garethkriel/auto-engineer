# crdt-toolkit

- **Description:** Immutable state-based CRDTs in package `ae.crdt`: `GCounter`, `PNCounter`, `LwwRegister<T>` (total-order tie-break), `OrSet<E>` (observed-remove, add wins). `merge` is a join (commutative, associative, idempotent).
- **Language:** java
- **Tags:** distributed, crdt, merge, convergence, gossip, simulation
- **Source round:** practice/java/20261118T1756Z
- **Tests:** `mvn -q -B test` (22 tests: scenarios, merge laws, seeded gossip simulation vs oracle).
- **Reuse notes:** test convergence by random local ops plus random pairwise merges, then full merge, compared against an oracle that tracks observed operations separately.
