# Task (level 20 capstone): `ae.succinct` rank/select bit vector, wavelet matrix, Elias-Fano

Overlap check: the 35 `benchmark/java` directory names (j01-roman ... j35-minisql) were read: none is a bit-vector / wavelet / compressed-sequence task (j23-segtree and j16-ranges are different problems). `skills/java` and earlier rounds contain no wavelet or Elias-Fano code (roaring-bitmap-index is a different structure). Assumed no overlap.

## API (package `ae.succinct`, standard library only)
- `BitVector` immutable, built with `BitVector.Builder(n).set(i).build()` or `BitVector.of(boolean[])`. `size()`, `get(i)`, `ones()`, `rank1(i)`/`rank0(i)` = count in `[0,i)` for `0<=i<=n`, `select1(k)`/`select0(k)` = position of the k-th (0-based) one/zero, `IndexOutOfBoundsException` when out of range. Two-level directory (cumulative count per 512-bit superblock + popcount inside), `select` by binary search on the directory then in-word scan: O(log n) time, no per-bit arrays. `checkInvariants()` throws `IllegalStateException("bitvector: ...")` for a corrupt directory or non-zero padding bits.
- `WaveletMatrix(int[] values)` (values >= 0, else `IllegalArgumentException`): `length()`, `access(i)`, `rank(v,i)` (occurrences of v in `[0,i)`), `select(v,k)` (position of the k-th occurrence, `IndexOutOfBoundsException` if none), `kthSmallest(l,r,k)` (0-based, over `[l,r)`), `countLess(l,r,x)`, `rangeCount(l,r,lo,hi)` (values in `[lo,hi)`), `quantile`-style use via kthSmallest. Values above the stored maximum behave as absent. `checkInvariants()`.
- `EliasFano(long[] sorted, long universe)` non-decreasing values in `[0,universe]` (else `IllegalArgumentException`): `size()`, `get(i)`, `nextGeq(x)` = index of the first element >= x or `size()`, `contains(x)`, `bitsUsed()` (low bits + upper bit vector, directory excluded) which must be <= `n*(2+ceil(log2(universe/n)))+64` for n>0.

## Design
Bit vector words are `long[]`, the directory is `int[]` of superblock prefix counts (8 words per superblock). Wavelet matrix: B = bit length of max value (min 1) levels, MSB first, each level stores the bit vector of the current order and the number of zeros; the next order is the stable partition zeros-then-ones (no per-node pointers). Elias-Fano: l = floor(log2(universe/n)) low bits packed into a `long[]`; upper part is a unary bit vector of `n + (universe>>l) + 1` bits where element i sets bit `(v>>l) + i`; `get(i) = ((select1(i)-i)<<l) | low(i)`; `nextGeq` jumps with `select0` then scans one bucket.

## Success criteria
- Literal tests (hand-derived), boundary sizes (0, 1, 63, 64, 65, 511, 512, 513, 1024), seeded randomized differential tests against naive oracles, corruption tests for the invariant checkers, `mvn -q -B clean test` green.
- A mutation pass over the main rules (each survivor is judged equivalent vs test gap).
