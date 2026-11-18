# Task: rollback union-find and offline dynamic connectivity (level 16, advanced data structures)

Overlap check: `benchmark/java/` has j08-graph (graph search), j15-topo, j23-segtree. This task is not graph search,
topological sort or a range-query segment tree: it is a union-find with undo plus an offline
divide-and-conquer over the timeline. No core algorithm matches; no benchmark text was read for it.

## Requirements (package `ae.dsu`)
1. `RollbackDsu(int n)` - elements `0..n-1`; union by size, NO path compression, so unions can be undone.
   - `find(x)`, `connected(a,b)`, `componentSize(x)`, `components()`
   - `boolean union(a,b)` - true iff two different components were merged (a no-op union is not recorded)
   - `int snapshot()` - opaque marker (number of recorded merges); `rollbackTo(int snapshot)` undoes merges back to it;
     `undo()` undoes the last merge (IllegalStateException if none).
   - Out-of-range elements -> IllegalArgumentException; `n < 0` -> IllegalArgumentException; bad snapshot -> IllegalArgumentException.
2. `OfflineConnectivity.answer(int n, List<Event> events)` returns, for each `Query` event in order, whether its endpoints are
   connected at that moment. `Event` is a sealed interface with records `Add(u,v)`, `Remove(u,v)`, `Query(u,v)`.
   - Edges are undirected and form a multiset: adding the same edge twice needs two removes. Self-loops are allowed and irrelevant.
   - `Remove` of an edge not currently present -> IllegalArgumentException (nothing partially returned).
   - Edges still present at the end simply stay until the end.
   - Must run in O((E+Q) log T log n), verified by a 20 000-event test with a time bound.

## Success criteria
`mvn -q -B clean test` passes: unit tests, validation tests, randomized differential test against a naive BFS oracle
(re-built from the current edge multiset at each query), and a large-input time-bounded test.
