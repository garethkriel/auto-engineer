# Task (level 20 capstone): Kademlia DHT simulated in-process

Overlap check: compared all 35 `benchmark/java` directory names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql). None is a DHT / XOR-metric routing task. Existing skill `consistent-hash-ring` is a different placement scheme (ring, no routing tables, no iterative lookup).

## Design (package `ae.kad`)
- `NodeId(long value)`: 64-bit id; `distance(o)` = XOR (compared unsigned); `bucketIndex(o)` = 63 - leadingZeros(xor), -1 if equal.
- `RoutingTable(self, k)`: 64 flat buckets (index = bucketIndex), each an LRU list (oldest first) of at most k ids plus a replacement cache (<= k, newest last).
  - `touch(id)`: self -> empty. Known id moves to tail. New id and bucket not full -> appended. New id and bucket full -> id goes to replacement cache and the bucket's OLDEST id is returned (caller should ping it).
  - `evict(id)`: removes it from bucket; the newest replacement (if any) is promoted to the tail.
  - `closest(target, n)`: up to n known ids sorted by ascending XOR distance to target.
- `Network`: synchronous simulated RPC fabric. Nodes can be set down. Optional `Fault` predicate drops individual calls. Counts RPCs. RPCs: ping, findNode, findValue, store. Unknown/down/dropped target = failure (empty/false).
- `KadNode(id, network, k, alpha)`: handlers touch the sender in the routing table; a full bucket records the stale candidate in a suspect set (no nested pings inside handlers). Operations `join(contact)`, `lookup(target)`, `put(key,value)`, `get(key)` finish by pinging suspects: failed ping -> evict, success -> keep (and move to tail).
  - `lookup(target)`: iterative; shortlist ordered by distance; each round queries up to alpha NEW ids among the k closest non-failed ones; failed ones are marked FAILED and evicted; stops when none of the k closest non-failed is NEW. Returns those (excluding self), nearest first.
  - `put`: store at the k closest nodes among lookup result + self. `get`: local value first, else iterative findValue that returns as soon as a node reports a value.

## Success criteria
- Routing table semantics above, closest() matches a brute-force oracle.
- 150-node network joined through one bootstrap node: lookup returns exactly the true k closest alive nodes for many random targets; still true after 25% of nodes go down; put/get round trips; get survives loss of some replicas.
- Lookup RPC counts are bounded; dropped RPCs are tolerated; down nodes get evicted from tables.
- `mvn -q -B test` passes.
