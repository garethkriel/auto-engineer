# Task 20261120T0356Z — erasure-coded blob store (level 20 capstone)

Overlap check: `benchmark/java/` names (roman … minisql) contain nothing about Galois fields, Reed-Solomon or
erasure coding. The topic is original.

## Goal
Package `ae.erasure`, standard library only: a GF(256) Reed-Solomon codec and a file-backed blob store that survives
the loss or corruption of up to `m` of its `n = k + m` shard files.

## Design
- `Gf256`: field GF(2^8) with reducing polynomial 0x11D, generator 2. `add`=xor, `mul`/`div`/`inv`/`pow` via log/exp
  tables. `inv(0)` and `div(x,0)` throw `ArithmeticException`. `pow(0,0)=1`, `pow(0,e>0)=0`.
- `GfMatrix`: immutable-ish byte matrix over GF(256): `identity(n)`, `multiply`, `invert` (Gauss-Jordan; throws
  `ArithmeticException` if singular), `subMatrix(rows[])`, `rows()`, `cols()`, `get`, `row(i)` copy.
- `ReedSolomon(k, m)`: `1<=k`, `1<=m`, `k+m<=256` else `IllegalArgumentException`. Systematic code: encoding matrix is
  `[I_k ; C]`, `C[i][j] = inv((k+i) xor j)` (Cauchy), so every k rows are invertible (MDS).
  - `byte[][] encode(byte[][] data)`: k equal-length data shards -> m parity shards.
  - `void reconstruct(byte[][] shards, boolean[] present)`: `shards` has n entries; entries with `present[i]=false`
    are overwritten (allocated if null) with the correct content. Needs >= k present, else `IllegalArgumentException`.
    All present shards must have equal length.
  - `boolean verify(byte[][] shards)`: parity recomputed from data equals the stored parity.
- `BlobStore(Path root, int k, int m)`: node `i` lives in `root/node-<i>/`. Blob names match `[A-Za-z0-9._-]+`, not
  starting with `.` (else `IllegalArgumentException`).
  - `put(name, bytes)`: shardLen = ceil(len/k) (0 for empty), data zero-padded, n shard files written
    temp-file-then-atomic-move; overwrites replace each shard.
  - `get(name)`: returns the original bytes if >= k valid shards. `NoSuchElementException` if no shard file exists for the
    name on any node; `UnrecoverableBlobException` (checked, extends IOException) if files exist but < k are valid.
  - `status(name)`: `List<ShardState>` (OK / MISSING / CORRUPT) per node.
  - `repair(name)`: rebuild every non-OK shard from the valid ones and rewrite it; returns the list of repaired node
    indices (ascending). Same exceptions as `get`.
  - `delete(name)`: removes shard files, returns true if any existed.
- Shard file layout (big endian): `magic "ERS1"(4) | k u16 | m u16 | index u16 | blobLen u64 | putId u64 | payloadLen u32 |
  payloadCrc u32 | headerCrc u32 | payload`. `HEADER_SIZE = 38`. `putId` is a random long chosen once per `put`, shared by its n shards. headerCrc is CRC32 over the first 34 bytes, payloadCrc over
  the payload. A shard is valid iff magic, headerCrc, k, m, index == node, payloadLen == ceil(blobLen/k) == file
  size - HEADER_SIZE, and payloadCrc all check out, AND its putId equals the putId shared by the most internally valid shards (ties: the larger putId wins),
  so a stale shard left over from an older `put` is reported CORRUPT even when its length is identical.

## Success criteria
`mvn -q -B clean test` green: field axioms exhaustive, multiplication cross-checked against carry-less multiply
oracle for all 65536 pairs, every k-subset erasure pattern for small (k,m), randomised large patterns, shard
corruption (truncate at every length, flip every byte of a small shard), stale shard detection, repair, and
mutation kill-checks.
