# Task: CPU cache hierarchy simulator (ae.cachesim)

Level 20 capstone. Overlap check: `ls benchmark/java` lists j01-roman, j02-wordfreq, j03-lru, j04-csv,
j05-intervals, j06-lcs, j07-expr, j08-graph, j09-bank, j10-counter, j11-streams, j12-fileindex, j13-ratelimit,
j14-json, j15-topo, j16-ranges, j17-cache, j18-diff, j19-allocate, j20-cron, j21-sheet, j22-regex, j23-segtree,
j24-queue, j25-bizhours, j26-rwlock, j27-primes, j28-script, j29-kvlog, j30-miniserver, j31-ranked, j32-fsm,
j33-pricing, j34-vclock, j35-minisql. j03-lru / j17-cache are key-value LRU caches; this task is a
hardware cache *geometry* simulator (address -> set/line, associativity, write policies, two-level
hierarchy, AMAT, Belady OPT bound) and shares no API or core algorithm with them. Assumption recorded: LRU is only
one of four replacement policies here.

## Design
- `Policy` {LRU, FIFO, PLRU (tree pseudo-LRU, ways power of two <= 32), RANDOM (seeded)}.
- `CacheConfig(sizeBytes, lineBytes, ways, policy, writeBack, seed)`; lineBytes and sets are powers of two,
  sizeBytes = sets * ways * lineBytes. Invalid config -> IllegalArgumentException.
- `Cache.access(addr, write)` -> `AccessResult(hit, fill, evictedLine, writeback, writeThrough)`.
  line = addr / lineBytes, set = line & (sets-1). Victim: first invalid way, otherwise per policy.
  - writeBack=true: write-allocate; a write sets the dirty bit; evicting a dirty line is a writeback (memWrites+1).
  - writeBack=false: write-through, no-allocate; every write adds memWrites+1, a write miss does not fill.
  - A fill adds memReads+1. `evictedLine` is a line number (addr/lineBytes) or `AccessResult.NONE` (-1).
- `CacheStats(reads, writes, readHits, writeHits, evictions, writebacks, memReads, memWrites)`.
- `Cache.residentLines()` sorted set of resident line numbers; `flush()` writes back dirty lines, returns count.
- `Hierarchy(l1, l2)`: L1 dirty eviction -> L2 write of the evicted line's byte address; L1 fill -> L2 read of addr;
  L1 write-through -> L2 write. `amat(l1, l2, mem) = l1 + l1MissRate * (l2 + l2LocalMissRate * mem)`.
- `OptSimulator.misses(lines, capacity)`: Belady optimal fully-associative miss count (lower bound).

## Success criteria
`mvn -q -B clean test` passes: literal traces (PLRU vs LRU divergence, FIFO Belady anomaly 9 vs 10 faults,
row/column matrix walk 64 vs 1024 misses), differential tests against naive per-set list oracles (LRU, FIFO,
write-back and write-through), 2-way PLRU == LRU, LRU stack property, OPT <= every policy, hierarchy and AMAT literals.
