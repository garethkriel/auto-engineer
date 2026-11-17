package dp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DynamicProgrammingTest {

    @Test
    void minCoinsClassicExample() {
        assertEquals(Optional.of(List.of(10, 1)), DynamicProgramming.minCoins(new int[] {1, 5, 10, 25}, 11));
    }

    @Test
    void minCoinsBeatsGreedy() {
        // greedy would give 4+1+1; optimal is 3+3
        assertEquals(Optional.of(List.of(3, 3)), DynamicProgramming.minCoins(new int[] {1, 3, 4}, 6));
    }

    @Test
    void minCoinsDescendingResult() {
        assertEquals(Optional.of(List.of(25, 10, 1)), DynamicProgramming.minCoins(new int[] {1, 5, 10, 25}, 36));
    }

    @Test
    void minCoinsZeroAndImpossible() {
        assertEquals(Optional.of(List.of()), DynamicProgramming.minCoins(new int[] {2}, 0));
        assertEquals(Optional.empty(), DynamicProgramming.minCoins(new int[] {2, 4}, 7));
    }

    @Test
    void countWaysKnown() {
        assertEquals(4, DynamicProgramming.countWays(new int[] {1, 2, 5}, 5));
        assertEquals(1, DynamicProgramming.countWays(new int[] {3}, 0));
        assertEquals(0, DynamicProgramming.countWays(new int[] {2}, 3));
        assertEquals(292, DynamicProgramming.countWays(new int[] {1, 5, 10, 25, 50}, 100));
    }

    @Test
    void countWaysIgnoresDuplicateCoinValues() {
        assertEquals(4, DynamicProgramming.countWays(new int[] {5, 1, 2, 2, 5}, 5));
    }

    @Test
    void countWaysOverflowThrows() {
        int[] coins = new int[200];
        for (int i = 0; i < coins.length; i++) {
            coins[i] = i + 1;
        }
        assertThrows(ArithmeticException.class, () -> DynamicProgramming.countWays(coins, 5000));
    }

    @Test
    void knapsackClassic() {
        DynamicProgramming.Knapsack k = DynamicProgramming.knapsack(new int[] {1, 3, 4, 5}, new int[] {1, 4, 5, 7}, 7);
        assertEquals(9, k.value());
        assertEquals(List.of(1, 2), k.items());
    }

    @Test
    void knapsackZeroCapacityTakesZeroWeightItems() {
        DynamicProgramming.Knapsack k = DynamicProgramming.knapsack(new int[] {0, 2}, new int[] {5, 9}, 0);
        assertEquals(5, k.value());
        assertEquals(List.of(0), k.items());
    }

    @Test
    void knapsackEmptyItems() {
        DynamicProgramming.Knapsack k = DynamicProgramming.knapsack(new int[0], new int[0], 10);
        assertEquals(0, k.value());
        assertTrue(k.items().isEmpty());
    }

    @Test
    void knapsackMatchesBruteForceAndIsConsistent() {
        Random rnd = new Random(42);
        for (int round = 0; round < 200; round++) {
            int n = rnd.nextInt(9);
            int[] w = new int[n];
            int[] v = new int[n];
            for (int i = 0; i < n; i++) {
                w[i] = rnd.nextInt(8);
                v[i] = rnd.nextInt(20);
            }
            int cap = rnd.nextInt(20);
            long best = 0;
            for (int mask = 0; mask < (1 << n); mask++) {
                long tw = 0;
                long tv = 0;
                for (int i = 0; i < n; i++) {
                    if ((mask >> i & 1) == 1) {
                        tw += w[i];
                        tv += v[i];
                    }
                }
                if (tw <= cap) {
                    best = Math.max(best, tv);
                }
            }
            DynamicProgramming.Knapsack k = DynamicProgramming.knapsack(w, v, cap);
            assertEquals(best, k.value());
            long tw = 0;
            long tv = 0;
            int prev = -1;
            for (int idx : k.items()) {
                assertTrue(idx > prev, "indices ascending and distinct");
                prev = idx;
                tw += w[idx];
                tv += v[idx];
            }
            assertTrue(tw <= cap);
            assertEquals(k.value(), tv);
        }
    }

    @Test
    void lisKnownLengths() {
        assertEquals(4, DynamicProgramming.longestIncreasingSubsequence(new int[] {10, 9, 2, 5, 3, 7, 101, 18}).length);
        assertEquals(1, DynamicProgramming.longestIncreasingSubsequence(new int[] {7, 7, 7}).length);
        assertEquals(0, DynamicProgramming.longestIncreasingSubsequence(new int[0]).length);
    }

    @Test
    void lisHandlesExtremeValues() {
        int[] a = {Integer.MAX_VALUE, Integer.MIN_VALUE, 0, Integer.MAX_VALUE};
        int[] r = DynamicProgramming.longestIncreasingSubsequence(a);
        assertArrayEquals(new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE}, r);
    }

    @Test
    void lisMatchesQuadraticReferenceAndIsValidSubsequence() {
        Random rnd = new Random(7);
        for (int round = 0; round < 300; round++) {
            int n = rnd.nextInt(30);
            int[] a = new int[n];
            for (int i = 0; i < n; i++) {
                a[i] = rnd.nextInt(15) - 5;
            }
            int[] dp = new int[n];
            int best = 0;
            for (int i = 0; i < n; i++) {
                dp[i] = 1;
                for (int j = 0; j < i; j++) {
                    if (a[j] < a[i]) {
                        dp[i] = Math.max(dp[i], dp[j] + 1);
                    }
                }
                best = Math.max(best, dp[i]);
            }
            int[] r = DynamicProgramming.longestIncreasingSubsequence(a);
            assertEquals(best, r.length);
            for (int i = 1; i < r.length; i++) {
                assertTrue(r[i - 1] < r[i]);
            }
            int pos = 0;
            for (int x : r) {
                while (pos < n && a[pos] != x) {
                    pos++;
                }
                assertTrue(pos < n, "result must be a subsequence");
                pos++;
            }
        }
    }

    @Test
    void inputsNotMutated() {
        int[] coins = {5, 1, 2};
        int[] copy = coins.clone();
        DynamicProgramming.minCoins(coins, 9);
        DynamicProgramming.countWays(coins, 9);
        assertArrayEquals(copy, coins);
        int[] a = {3, 1, 2};
        DynamicProgramming.longestIncreasingSubsequence(a);
        assertArrayEquals(new int[] {3, 1, 2}, a);
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.minCoins(null, 1));
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.minCoins(new int[0], 1));
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.minCoins(new int[] {0}, 1));
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.minCoins(new int[] {1}, -1));
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.countWays(new int[] {-2}, 1));
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.knapsack(new int[] {1}, new int[] {1, 2}, 1));
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.knapsack(new int[] {-1}, new int[] {1}, 1));
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.knapsack(new int[] {1}, new int[] {1}, -1));
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.longestIncreasingSubsequence(null));
    }
}
