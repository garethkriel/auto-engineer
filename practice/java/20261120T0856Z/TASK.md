# Task: suffix-array toolkit and FM-index (level 20 capstone)

Package `ae.suffix`, standard library only, no benchmark overlap
(checked: `benchmark/java/` has no suffix array / BWT / FM-index task; j06-lcs is the
longest common *subsequence* DP and j02/j12 are counting/indexing - this task is
suffix sorting, longest common *substring*, BWT and backward search).

## Design
- `SuffixArray.build(int[])` prefix doubling with counting sort, O(n log n);
  `lcp(int[], int[] sa)` Kasai; `lcp[i] = lcp(suffix sa[i-1], suffix sa[i])`, `lcp[0]=0`.
  Alphabet is any int values (negative allowed), compared as ints.
- `SuffixIndex(String)`: `count/occurrences(pattern)` (binary search on SA; occurrences
  ascending; empty pattern -> IllegalArgumentException), `contains`,
  `longestRepeatedSubstring()` (overlaps allowed; ties -> lexicographically smallest; "" if none),
  `distinctSubstrings()` (long), `kthSubstring(k)` (1-based, lexicographic over distinct
  substrings, IAE if out of range), static `longestCommonSubstring(a, b)` (ties -> lexicographically smallest).
- `Bwt.transform(String)` appends sentinel `'\0'` (input must not contain it) and returns the
  BWT string (length n+1); `Bwt.inverse(String)` returns the original; it throws
  IllegalArgumentException for strings that are not a valid BWT (no/many sentinels, or not the
  BWT of anything).
- `FmIndex(String text, int saStep)`: backward-search `count(pattern)` and `locate(pattern)`
  (ascending) using occ checkpoints every 16 rows and a text-position-sampled suffix array
  (every `saStep`-th text position). Patterns containing `'\0'` match nothing. Empty pattern -> IAE.
  `saStep < 1` or text containing `'\0'` -> IAE.

## Success criteria
`mvn -q -B clean test` passes: known answers (banana), seeded randomized differential tests
against naive oracles (sorted suffixes, indexOf, TreeSet of substrings, brute-force LCSubstring),
BWT round trips and invalid-input rejection, a 300k-character build within a time bound.
