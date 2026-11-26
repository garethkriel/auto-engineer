# ot-text-editor

- **Description:** operational transformation core for collaborative text editing: normalised `TextOp` (retain/insert/delete) with `apply`, `compose`, `transform` (first argument wins insert ties) and `invert`; a log-based `Server`; a three-state `Client` (Synchronized / AwaitingAck / AwaitingAckWithBuffer); and a seeded `Simulation` with per-client FIFO channels used to test convergence.
- **Language:** java (JDK 21, JUnit 5, standard library only)
- **Tags:** ot, collaborative-editing, convergence, simulation, state-machine
- **Source round:** practice/java/20261125T2356Z
- **Tests:** `mvn -q -B test` (33 tests: literals, randomised compose/transform/invert properties, protocol guards, 60+30 seeded convergence runs).
- **Gotcha:** the canonical form moves an insert before an adjacent delete, so which side of a deleted run an insert sits on is lost; TP1 still holds but `transform(compose(a1,a2),b)` need not equal stepwise transformation.
