# kademlia-dht

- **Description:** Kademlia DHT simulated in-process: 64-bit `NodeId` with unsigned XOR metric, `RoutingTable` (flat 64 LRU buckets + replacement caches, `touch` returns the stale candidate to ping), synchronous `Network` with down nodes and a `Fault` predicate, `KadNode` with iterative `lookup` (alpha/k, one retry before eviction), `put`/`get`, bucket `refresh`, deferred suspect pings (handlers never issue RPCs).
- **Language:** java
- **Tags:** dht, kademlia, routing-table, distributed, simulation, xor-metric, iterative-lookup
- **Source round:** practice/java/20261125T1357Z
- **Notes:** Join must also `refresh()` far buckets; lookups after mass failure need a repair refresh. One known cost-only mutant survives (querying beyond the k closest does not change results).
