# Task: backtracking toolkit (level 9, recursion/backtracking)

Package `ae`, class `Backtrack` (static methods, all results are fresh lists; inputs never mutated):

- `List<List<Integer>> uniquePermutations(int[] values)`: all distinct permutations of a multiset, in lexicographic order. `[]` -> `[[]]`. null -> IllegalArgumentException.
- `List<int[]> nQueens(int n)`: every placement of n non-attacking queens; each int[] gives the column of the queen in each row; solutions in lexicographic order. n<1 -> IllegalArgumentException. n=2,3 -> empty.
- `List<List<Integer>> combinationSum(int[] candidates, int target)`: all unique combinations (non-decreasing order, each candidate reusable any number of times) summing to target. Candidates must be positive (else IAE); duplicates in candidates ignored; target<0 -> IAE; target 0 -> `[[]]`. Result ordered lexicographically. Overflow-safe (use long).

Success: `mvn -q -B test` passes.

Assumptions / overlap check: benchmark/ names are roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex. None is a backtracking/combinatorics task; the core algorithm differs from all.
