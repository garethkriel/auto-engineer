package ae.succinct;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EliasFanoTest {

    // n=7, u=24, l = floor(log2(24/7)) = floor(1.77) = 1 low bit
    static final long[] S = {2, 3, 5, 7, 11, 13, 24};

    @Test
    void literalGetAndSearch() {
        EliasFano e = new EliasFano(S, 24);
        assertEquals(7, e.size());
        for (int i = 0; i < S.length; i++) assertEquals(S[i], e.get(i));
        assertEquals(0, e.nextGeq(0));
        assertEquals(0, e.nextGeq(2));
        assertEquals(1, e.nextGeq(3));
        assertEquals(2, e.nextGeq(4));
        assertEquals(5, e.nextGeq(12));
        assertEquals(6, e.nextGeq(14));
        assertEquals(6, e.nextGeq(24));
        assertEquals(7, e.nextGeq(25));
        assertEquals(7, e.nextGeq(Long.MAX_VALUE));
        assertEquals(0, e.nextGeq(-5));
        assertTrue(e.contains(13));
        assertFalse(e.contains(12));
        assertFalse(e.contains(25));
        assertFalse(e.contains(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> e.get(7));
        assertThrows(IndexOutOfBoundsException.class, () -> e.get(-1));
    }

    @Test
    void duplicatesAndZeros() {
        long[] d = {0, 0, 0, 5, 5, 9};
        EliasFano e = new EliasFano(d, 9);
        for (int i = 0; i < d.length; i++) assertEquals(d[i], e.get(i));
        assertEquals(0, e.nextGeq(0)); // first of the equal run
        assertEquals(3, e.nextGeq(1));
        assertEquals(3, e.nextGeq(5));
        assertEquals(5, e.nextGeq(6));
        assertTrue(e.contains(0));
        assertTrue(e.contains(5));
        assertFalse(e.contains(4));
    }

    @Test
    void degenerateShapes() {
        EliasFano empty = new EliasFano(new long[0], 100);
        assertEquals(0, empty.size());
        assertEquals(0, empty.nextGeq(0));
        assertFalse(empty.contains(0));
        assertEquals(0, empty.bitsUsed());

        EliasFano one = new EliasFano(new long[] {0}, 0);
        assertEquals(0, one.get(0));
        assertEquals(0, one.nextGeq(0));
        assertEquals(1, one.nextGeq(1));

        EliasFano allZero = new EliasFano(new long[] {0, 0, 0, 0}, 0);
        assertEquals(0, allZero.get(3));
        assertEquals(4, allZero.nextGeq(1));

        EliasFano dense = new EliasFano(new long[] {0, 1, 2, 3, 4, 5, 6, 7}, 7); // l = 0
        for (int i = 0; i < 8; i++) assertEquals(i, dense.get(i));
        assertEquals(5, dense.nextGeq(5));

        EliasFano sparse = new EliasFano(new long[] {1L << 40, (1L << 41) + 3}, 1L << 42);
        assertEquals(1L << 40, sparse.get(0));
        assertEquals((1L << 41) + 3, sparse.get(1));
        assertEquals(1, sparse.nextGeq((1L << 40) + 1));
        assertEquals(2, sparse.nextGeq((1L << 41) + 4));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> new EliasFano(new long[] {3, 2}, 10));
        assertThrows(IllegalArgumentException.class, () -> new EliasFano(new long[] {-1, 2}, 10));
        assertThrows(IllegalArgumentException.class, () -> new EliasFano(new long[] {1, 11}, 10));
        assertThrows(IllegalArgumentException.class, () -> new EliasFano(new long[] {1}, -1));
        assertThrows(NullPointerException.class, () -> new EliasFano(null, 5));
    }

    @Test
    void randomDifferential() {
        Random rnd = new Random(1234567);
        for (int round = 0; round < 120; round++) {
            int n = rnd.nextInt(300);
            long u = round % 3 == 0 ? n + rnd.nextInt(5) : (long) rnd.nextInt(1 << (4 + round % 20)) + 1;
            long[] s = new long[n];
            for (int i = 0; i < n; i++) s[i] = (long) (rnd.nextDouble() * (u + 1));
            Arrays.sort(s);
            EliasFano e = new EliasFano(s, u);
            for (int i = 0; i < n; i++) assertEquals(s[i], e.get(i), "n=" + n + " u=" + u + " i=" + i);
            for (int q = 0; q < 120; q++) {
                long x = (long) (rnd.nextDouble() * (u + 3)) - 1;
                int expect = lowerBound(s, x);
                assertEquals(expect, e.nextGeq(x), "n=" + n + " u=" + u + " x=" + x);
                assertEquals(expect < n && s[expect] == x, e.contains(x));
            }
        }
    }

    static int lowerBound(long[] s, long x) {
        int lo = 0, hi = s.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (s[mid] < x) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    @Test
    void spaceBoundHolds() {
        Random rnd = new Random(8);
        for (int round = 0; round < 40; round++) {
            int n = 1 + rnd.nextInt(2000);
            long u = n + rnd.nextInt(1 << (1 + round % 24));
            long[] s = new long[n];
            for (int i = 0; i < n; i++) s[i] = (long) (rnd.nextDouble() * (u + 1));
            Arrays.sort(s);
            EliasFano e = new EliasFano(s, u);
            int logRatio = 64 - Long.numberOfLeadingZeros(Math.max(1, u / n)); // ceil-ish upper bound of log2(u/n)+1
            long bound = (long) n * (2 + logRatio) + 64;
            assertTrue(e.bitsUsed() <= bound, "n=" + n + " u=" + u + " used=" + e.bitsUsed() + " bound=" + bound);
            // and far below 64 bits per element when the universe is small
            if (u < (1L << 20)) assertTrue(e.bitsUsed() < 64L * n, "n=" + n + " u=" + u);
        }
    }

    @Test
    void bitsUsedMatchesTheDocumentedLayoutExactly() {
        // l = floor(log2(u/n)), bits = n*l + (n + (u>>l) + 1)
        // n=7 u=24: u/n=3 -> l=1 -> 7 + (7 + 12 + 1) = 27
        assertEquals(27, new EliasFano(S, 24).bitsUsed());
        // n=4 u=64: u/n=16 -> l=4 -> 16 + (4 + 4 + 1) = 25
        assertEquals(25, new EliasFano(new long[] {1, 20, 40, 64}, 64).bitsUsed());
        // n=8 u=7: u/n=0 -> l=0 -> 0 + (8 + 7 + 1) = 16
        assertEquals(16, new EliasFano(new long[] {0, 1, 2, 3, 4, 5, 6, 7}, 7).bitsUsed());
    }

    @Test
    void bigSequenceSearchesAreFastEnough() {
        int n = 200_000;
        long[] s = new long[n];
        long v = 0;
        Random rnd = new Random(3);
        for (int i = 0; i < n; i++) {
            v += rnd.nextInt(50);
            s[i] = v;
        }
        EliasFano e = new EliasFano(s, v);
        long start = System.nanoTime();
        long sink = 0;
        for (int q = 0; q < 200_000; q++) sink += e.nextGeq(rnd.nextInt((int) Math.min(Integer.MAX_VALUE, v)));
        assertTrue(sink > 0);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5000, "searches too slow");
    }
}
