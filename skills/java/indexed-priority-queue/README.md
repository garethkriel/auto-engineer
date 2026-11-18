# indexed-priority-queue

- **Description:** `ae.pq.IndexedPriorityQueue<K>`: min-priority queue over int ids with O(log n) `changeKey` and `remove(id)`, O(1) `contains`, d-ary heap (arity 2..16), ties broken by smaller id. Useful for Dijkstra/Prim, schedulers, top-k with updates.
- **Language:** java
- **Tags:** heap, priority-queue, d-ary, decrease-key, indexed
- **Source round:** practice/java/20261118T0556Z
- **Tests:** `mvn -q -B test` (17 tests incl. randomized TreeSet differential for arities 2,3,4,8,16).
