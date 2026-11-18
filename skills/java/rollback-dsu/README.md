# rollback-dsu

- **Description:** Union-find with undo (`RollbackDsu`: union by size, no path compression, `snapshot()`/`rollbackTo()`/`undo()`) and `OfflineConnectivity.answer(n, events)` answering connectivity queries over a timeline of `Add`/`Remove`/`Query` events (multiset edges, self-loops allowed) by divide and conquer on the timeline.
- **Language:** java
- **Tags:** union-find, dsu, rollback, offline, dynamic-connectivity, divide-and-conquer, sealed-interface
- **Source round:** practice/java/20261118T0756Z
- **Reuse notes:** the "intervals over a timeline spread on a segment tree + DFS with rollback" pattern works for any undoable structure. Tests include a BFS oracle differential and a 20k-event timed test.
