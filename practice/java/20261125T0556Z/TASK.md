# Task 20261125T0556Z: Merkle map with proofs and anti-entropy sync (level 20 capstone)

Overlap check: `benchmark/java/` contains j01-roman, j02-wordfreq, j03-lru, j04-csv, j05-intervals, j06-lcs, j07-expr, j08-graph, j09-bank, j10-counter, j11-streams, j12-fileindex, j13-ratelimit, j14-json, j15-topo, j16-ranges, j17-cache, j18-diff, j19-allocate, j20-cron, j21-sheet, j22-regex, j23-segtree, j24-queue, j25-bizhours, j26-rwlock, j27-primes, j28-script, j29-kvlog, j30-miniserver, j31-ranked, j32-fsm, j33-pricing, j34-vclock, j35-minisql. None is a hash tree, proof system or replica reconciliation (j34-vclock is vector clocks, j29-kvlog an append log). No overlap.

## Design (package `ae.merkle`)
- `MerkleMap`: String->String map stored as a fixed-shape 16-ary hash trie of depth 4 keyed by the first 4 hex nibbles of SHA-256(UTF-8 key) (65536 buckets).
  - Bucket (depth 4) hash = SHA-256(0x00 || for each entry in key order: int32 len(key), key bytes, int32 len(value), value bytes).
  - Inner hash = SHA-256(0x01 || the 16 child hashes). Absent subtrees hash as `Hashing.EMPTY[level]` (the hash of a node with no entries), so the root depends only on the content, never on history.
  - Empty nodes are pruned (invariant). Cached hashes always equal recomputation (invariant). `checkInvariants()` throws `IllegalStateException` with a message starting `invariant:`.
  - API: `put` (returns previous Optional), `remove`, `get`, `size`, `rootHash`, `hashAt(path)`, `childHashes(path)`, `bucket(path)`, `prove(key)`, `copy()`, `entries()`.
- `Proof` + `MerkleMap.verify(root, key, valueOrEmpty, proof)`: inclusion and non-inclusion proofs (bucket entries + 4 x 15 sibling hashes). Verification is strict: bucket sorted and unique, every entry's path equals the bucket path, sibling shape exact.
- `Remote` interface (`hashAt`, `childHashes`, `bucket`) and `Sync.diff(local, remote)` / `Sync.pull(local, remote)`: top-down comparison, every response verified against the hash its parent advertised; a lying or malformed remote raises `IllegalStateException` and leaves `local` untouched. Request counts are reported.

## Success criteria
- `mvn -q -B clean test` green.
- Root equals an independent from-scratch oracle after every step of seeded random put/remove runs vs a `TreeMap`; insertion-order independence.
- Proofs: valid ones verify; every single-byte flip of any sibling/bucket byte, wrong key/value/root and malformed shapes fail; control inputs differing only by the defect pass.
- Sync: equal maps cost exactly 1 request; k differing keys cost at most 5k requests; diff equals the symmetric-difference oracle; pull converges; hostile remotes are rejected without mutating local.
- A mutation check over the production code kills every non-equivalent mutant.
