# ot-text-collab

- **Description:** Operational transformation for collaborative plain-text editing: canonical `TextOp` (retain/insert/delete) with apply, compose, invert, transform (TP1, first argument wins insert ties) and cursor mapping; `OtServer` linear history; `OtClient` Jupiter state machine (synced / awaiting ack / awaiting with buffer); seeded `OtSimulation` over FIFO channels with a base+pending invariant.
- **Language:** java
- **Tags:** ot, collaboration, text, transform, compose, jupiter, simulation, property-tests
- **Source round:** practice/java/20261126T0556Z
- **Tests:** `mvn -q -B test` (48 tests)
- **Notes:** UTF-16 code units; the committed op wins ties on the server; client rejects non-consecutive revisions.
