package dp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Classic dynamic-programming routines: coin change, 0/1 knapsack, longest increasing subsequence. */
public final class DynamicProgramming {

    /** Result of {@link #knapsack}: total value and chosen item indices in ascending order. */
    public record Knapsack(long value, List<Integer> items) {
        public Knapsack {
            items = List.copyOf(items);
        }
    }

    private DynamicProgramming() {
    }

    private static void checkCoins(int[] coins, int amount) {
        if (coins == null || coins.length == 0) {
            throw new IllegalArgumentException("coins must be non-empty");
        }
        for (int c : coins) {
            if (c <= 0) {
                throw new IllegalArgumentException("coin values must be positive: " + c);
            }
        }
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
    }

    /** Fewest coins (unlimited supply) summing to amount, descending; empty if impossible. */
    public static Optional<List<Integer>> minCoins(int[] coins, int amount) {
        checkCoins(coins, amount);
        int[] best = new int[amount + 1];
        int[] last = new int[amount + 1];
        Arrays.fill(best, Integer.MAX_VALUE);
        best[0] = 0;
        for (int a = 1; a <= amount; a++) {
            for (int c : coins) {
                if (c <= a && best[a - c] != Integer.MAX_VALUE && best[a - c] + 1 < best[a]) {
                    best[a] = best[a - c] + 1;
                    last[a] = c;
                }
            }
        }
        if (best[amount] == Integer.MAX_VALUE) {
            return Optional.empty();
        }
        List<Integer> result = new ArrayList<>();
        for (int a = amount; a > 0; a -= last[a]) {
            result.add(last[a]);
        }
        result.sort(Collections.reverseOrder());
        return Optional.of(List.copyOf(result));
    }

    /** Number of distinct coin multisets summing to amount; ArithmeticException on overflow. */
    public static long countWays(int[] coins, int amount) {
        checkCoins(coins, amount);
        int[] distinct = Arrays.stream(coins).distinct().toArray();
        long[] ways = new long[amount + 1];
        ways[0] = 1;
        for (int c : distinct) {
            for (int a = c; a <= amount; a++) {
                ways[a] = Math.addExact(ways[a], ways[a - c]);
            }
        }
        return ways[amount];
    }

    /** 0/1 knapsack with reconstruction of the chosen item indices. */
    public static Knapsack knapsack(int[] weights, int[] values, int capacity) {
        if (weights == null || values == null || weights.length != values.length) {
            throw new IllegalArgumentException("weights and values must be non-null and equal length");
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must be >= 0");
        }
        int n = weights.length;
        for (int i = 0; i < n; i++) {
            if (weights[i] < 0 || values[i] < 0) {
                throw new IllegalArgumentException("weights and values must be >= 0");
            }
        }
        long[][] t = new long[n + 1][capacity + 1];
        for (int i = 1; i <= n; i++) {
            int w = weights[i - 1];
            for (int c = 0; c <= capacity; c++) {
                t[i][c] = t[i - 1][c];
                if (w <= c) {
                    t[i][c] = Math.max(t[i][c], t[i - 1][c - w] + values[i - 1]);
                }
            }
        }
        List<Integer> chosen = new ArrayList<>();
        int c = capacity;
        for (int i = n; i >= 1; i--) {
            if (t[i][c] != t[i - 1][c]) {
                chosen.add(i - 1);
                c -= weights[i - 1];
            }
        }
        Collections.reverse(chosen);
        return new Knapsack(t[n][capacity], chosen);
    }

    /** One strictly increasing longest subsequence in O(n log n) (patience sorting with back-pointers). */
    public static int[] longestIncreasingSubsequence(int[] a) {
        if (a == null) {
            throw new IllegalArgumentException("array must not be null");
        }
        int n = a.length;
        int[] tails = new int[n]; // tails[k] = index of smallest tail of an increasing run of length k+1
        int[] prev = new int[n];
        int len = 0;
        for (int i = 0; i < n; i++) {
            int lo = 0;
            int hi = len;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (a[tails[mid]] < a[i]) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            prev[i] = lo > 0 ? tails[lo - 1] : -1;
            tails[lo] = i;
            if (lo == len) {
                len++;
            }
        }
        int[] result = new int[len];
        int idx = len > 0 ? tails[len - 1] : -1;
        for (int k = len - 1; k >= 0; k--) {
            result[k] = a[idx];
            idx = prev[idx];
        }
        return result;
    }
}
