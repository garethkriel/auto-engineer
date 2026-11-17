# Task: DP toolkit (level 9, dynamic programming)

Package `dp`, class `DynamicProgramming` (final, static methods only):

- `static Optional<List<Integer>> minCoins(int[] coins, int amount)` - fewest coins summing to `amount` (unlimited supply), returned in descending order; `Optional.empty()` if impossible; amount 0 -> empty list.
- `static long countWays(int[] coins, int amount)` - number of distinct multisets of coins summing to `amount` (order ignored, duplicate coin values in the input count once). Throws `ArithmeticException` on `long` overflow.
- `static Knapsack knapsack(int[] weights, int[] values, int capacity)` - 0/1 knapsack; `record Knapsack(long value, List<Integer> items)` with chosen indices ascending.
- `static int[] longestIncreasingSubsequence(int[] a)` - one strictly increasing longest subsequence, O(n log n).

Validation: null args, coins empty or <= 0, amount/capacity < 0, mismatched lengths, negative weights/values -> `IllegalArgumentException`.
Inputs are never mutated.

## Success criteria
`mvn -q -B test` passes, including brute-force cross-checks on small random inputs.

## Assumptions
- Overlap check: benchmark/java contains roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex. Coin change / knapsack / LIS are not LCS (different recurrence and domain), so no overlap.
- Duplicate coin values are collapsed for counting.
