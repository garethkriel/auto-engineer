# suffix-toolkit

- **Language:** Java 21, standard library only
- **Tags:** suffix-array, lcp, kasai, bwt, fm-index, substring-search, string-algorithms
- **Source round:** practice/java/20261120T0856Z

`ae.suffix`: `SuffixArray.build/lcp` (prefix doubling with counting sort, O(n log n); any int alphabet),
`SuffixIndex` (count/occurrences by binary search, longest repeated substring, distinct substring count,
k-th distinct substring, longest common substring of two strings via unique separator),
`Bwt` (sentinel `'\0'`, validated inverse) and `FmIndex` (backward search, occ checkpoints every 16 rows,
text-position-sampled SA for `locate`). Tests compare with naive oracles on seeded random input and
include a 300k-character time-bounded run (build and Kasai on periodic input).
Run: `mvn -q -B test`.
