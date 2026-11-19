# lock-manager

- **Description:** Deterministic, non-blocking multi-granularity lock manager (`ae.locks`): IS/IX/S/SIX/X modes with compatibility matrix and join lattice, path hierarchy with automatic intention locks, strict-FIFO queues, upgrades queued ahead of fresh requests, resumable multi-step acquire plans, wait-for-graph deadlock detection with youngest-victim abort, early `unlock` with a 2PL shrinking phase, and a package-private `checkInvariants()`.
- **Language:** java
- **Tags:** locking, deadlock-detection, two-phase-locking, hierarchy, simulation, capstone
- **Source round:** 20261119T0456Z (`practice/java/20261119T0456Z/TASK.md` has the full contract)
- **Tests:** `mvn -q -B test` (37 tests: literal matrix/join tables, scenario tests, seeded random simulations checking invariants after every step against an independent "who is blocked" model, a flat FIFO oracle).

Reusable ideas: return GRANTED/WAITING/ABORTED plus an events record instead of blocking so schedules are reproducible; build wait-for edges from holders *and* earlier incompatible queue entries (FIFO edges matter); give every operation one `resolveDeadlocks` pass after it, because releases can resume plans that block again.
