# succinct-structures

- **Description:** `ae.succinct` compact static structures: `BitVector` (rank1/rank0 in O(1) via a per-512-bit superblock directory, select1/select0 by binary search over the directory plus an in-word scan, invariant checker), `WaveletMatrix` over non-negative ints (access, rank, select, kthSmallest, countLess, rangeCount in O(log sigma) rank calls), `EliasFano` for sorted long sequences (get via select1, nextGeq via select0 jump, `bitsUsed` = n*l + n + (u>>l) + 1 with l = floor(log2(u/n))).
- **Language:** java
- **Tags:** succinct, rank-select, bit-vector, wavelet-matrix, elias-fano, range-queries, compressed-index, differential-testing
- **Source round:** practice/java/20261125T0957Z
- **Design:** wavelet levels are stable partitions (zeros first) so an index maps to `rank0(i)` or `zeros + rank1(i)`; select walks back up with select0/select1. Elias-Fano upper part is unary: element i sets bit `(v>>l)+i`.
- **Testing pattern:** hand-derived literal fixtures plus seeded differentials against prefix tables / sorted subarrays; one hand-built corrupt structure per invariant-checker rule; exact space formulas; 18-mutant check (survivors were equivalent).
- **Tests:** `mvn -q -B test` (26 tests).
