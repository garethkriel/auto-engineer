# Task: probabilistic sketches (package `ae.sketch`)

Level 20 (capped at 10 for recording). Topic: probabilistic data structures.
Overlap check against `benchmark/java/` names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter,
streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours,
rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql): none is a sketch/Bloom/HLL task.
Not a top-k word counter: `CountMinSketch` is an approximate frequency table over opaque keys, no ranking.

## Classes
- `Hash64`: seeded 64-bit hash of `byte[]`/`String` (UTF-8), deterministic across runs, well mixed.
- `BloomFilter`: `forExpected(long n, double fpp)` sizing m=ceil(-n ln p/(ln2)^2), k=round(m/n ln2) (min 1);
  `add`, `mightContain` (never a false negative), `union` (same m,k else IAE), `estimatedCount()`,
  `toBytes()`/`fromBytes()` (magic, params, CRC32; corrupt/truncated input -> `IllegalArgumentException`).
- `CountMinSketch`: `forErrorBounds(eps, delta)` width=ceil(e/eps), depth=ceil(ln(1/delta)); `add(key,count)`,
  `estimate` (never below true count), optional conservative update, `merge` (same shape), `total()`.
- `HyperLogLog`: precision p in 4..16, `add`, `estimate()` with small-range linear counting, `merge`
  (register-wise max), `standardError()`=1.04/sqrt(2^p).

## Success criteria
`mvn -q -B clean test` passes: sizing literals, no false negatives, measured FPP within bound on seeded data,
count-min one-sided error and bound, HLL within 3 standard errors, merge algebra (idempotent, commutative,
equals sketch of union), serialization round trip and corruption detection, argument validation.

## Assumptions
Keys are Strings or byte arrays. No thread safety required (documented). Counts are non-negative longs, saturating
is not needed: overflow throws via `Math.addExact`.
