package ae.suffix;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SuffixArrayTest {
    @Test
    void bananaSuffixArray() {
        assertArrayEquals(new int[] {5, 3, 1, 0, 4, 2}, SuffixArray.build(Naive.ints("banana")));
    }

    @Test
    void bananaLcp() {
        int[] t = Naive.ints("banana");
        assertArrayEquals(new int[] {0, 1, 3, 0, 0, 2}, SuffixArray.lcp(t, SuffixArray.build(t)));
    }

    @Test
    void emptyAndSingle() {
        assertEquals(0, SuffixArray.build(new int[0]).length);
        assertEquals(0, SuffixArray.lcp(new int[0], new int[0]).length);
        assertArrayEquals(new int[] {0}, SuffixArray.build(new int[] {7}));
        assertArrayEquals(new int[] {0}, SuffixArray.lcp(new int[] {7}, new int[] {0}));
    }

    @Test
    void allEqualCharactersSortShortestFirst() {
        int[] t = Naive.ints("aaaa");
        assertArrayEquals(new int[] {3, 2, 1, 0}, SuffixArray.build(t));
        assertArrayEquals(new int[] {0, 1, 2, 3}, SuffixArray.lcp(t, SuffixArray.build(t)));
    }

    @Test
    void negativeValuesAreOrderedAsInts() {
        int[] t = {3, -1, 3, -5, 0};
        assertArrayEquals(Naive.suffixArray(t), SuffixArray.build(t));
        assertEquals(3, SuffixArray.build(t)[0]); // suffix starting with -5 is smallest
    }

    @Test
    void inputIsNotModified() {
        int[] t = {2, 1, 2, 1};
        int[] copy = t.clone();
        SuffixArray.build(t);
        assertArrayEquals(copy, t);
    }

    @Test
    void randomDifferentialAgainstSortedSuffixes() {
        Random r = new Random(42);
        for (int iter = 0; iter < 600; iter++) {
            String s = Naive.random(r, r.nextInt(61), 1 + r.nextInt(4));
            int[] t = Naive.ints(s);
            int[] sa = SuffixArray.build(t);
            assertArrayEquals(Naive.suffixArray(t), sa, s);
            int[] lcp = SuffixArray.lcp(t, sa);
            for (int i = 1; i < sa.length; i++) {
                int h = 0;
                while (sa[i] + h < t.length && sa[i - 1] + h < t.length
                        && t[sa[i] + h] == t[sa[i - 1] + h]) h++;
                assertEquals(h, lcp[i], s + " at " + i);
            }
        }
    }

    @Test
    void largeInputsBuildQuickly() {
        Random r = new Random(7);
        int n = 300_000;
        int[] rnd = new int[n];
        int[] periodic = new int[n];
        for (int i = 0; i < n; i++) {
            rnd[i] = r.nextInt(4);
            periodic[i] = i % 3; // worst case for doubling: many rounds
        }
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int[] t : new int[][] {rnd, periodic, new int[n]}) {
                int[] sa = SuffixArray.build(t);
                boolean[] seen = new boolean[n];
                for (int p : sa) seen[p] = true;
                for (boolean b : seen) assertTrue(b);
                for (int i = 1; i < n; i += 997) assertTrue(suffixLess(t, sa[i - 1], sa[i]));
                long sum = 0; // linear-time Kasai: a reset-to-zero variant would take ~n^2 on periodic input
                for (int h : SuffixArray.lcp(t, sa)) sum += h;
                assertTrue(sum >= 0);
            }
        });
    }

    private static boolean suffixLess(int[] t, int a, int b) {
        while (a < t.length && b < t.length) {
            if (t[a] != t[b]) return t[a] < t[b];
            a++;
            b++;
        }
        return a >= t.length && b < t.length;
    }
}
