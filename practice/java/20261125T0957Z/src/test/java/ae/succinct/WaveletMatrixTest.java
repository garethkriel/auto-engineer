package ae.succinct;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WaveletMatrixTest {

    // idx:  0 1 2 3 4 5 6 7 8 9 10 11
    static final int[] A = {5, 4, 5, 5, 2, 1, 5, 6, 1, 3, 5, 0};

    @Test
    void literalAccessRankSelect() {
        WaveletMatrix w = new WaveletMatrix(A);
        assertEquals(12, w.length());
        for (int i = 0; i < A.length; i++) assertEquals(A[i], w.access(i), "access " + i);
        assertEquals(5, w.rank(5, 12));
        assertEquals(3, w.rank(5, 4)); // positions 0,2,3
        assertEquals(0, w.rank(5, 0));
        assertEquals(2, w.rank(1, 12));
        assertEquals(0, w.rank(7, 12)); // 7 fits in 3 bits but is absent
        assertEquals(0, w.rank(1000, 12)); // above the stored maximum
        assertEquals(0, w.rank(-3, 12));
        assertEquals(6, w.select(5, 3)); // occurrences of 5 at 0,2,3,6,10
        assertEquals(10, w.select(5, 4));
        assertEquals(8, w.select(1, 1));
        assertEquals(11, w.select(0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> w.select(5, 5));
        assertThrows(IndexOutOfBoundsException.class, () -> w.select(7, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> w.select(1000, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> w.select(5, -1));
        w.checkInvariants();
    }

    @Test
    void literalRangeQueries() {
        WaveletMatrix w = new WaveletMatrix(A);
        // [2,9) holds 5,5,2,1,5,6,1 -> sorted 1,1,2,5,5,5,6
        assertEquals(1, w.kthSmallest(2, 9, 0));
        assertEquals(1, w.kthSmallest(2, 9, 1));
        assertEquals(2, w.kthSmallest(2, 9, 2));
        assertEquals(5, w.kthSmallest(2, 9, 3));
        assertEquals(6, w.kthSmallest(2, 9, 6));
        assertThrows(IndexOutOfBoundsException.class, () -> w.kthSmallest(2, 9, 7));
        assertThrows(IndexOutOfBoundsException.class, () -> w.kthSmallest(2, 9, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> w.kthSmallest(3, 3, 0));
        // whole array < 5 : 4,2,1,1,3,0
        assertEquals(6, w.countLess(0, 12, 5));
        assertEquals(0, w.countLess(0, 12, 0));
        assertEquals(0, w.countLess(0, 12, -4));
        assertEquals(7, w.countLess(2, 9, 100));
        assertEquals(12, w.countLess(0, 12, 7)); // max is 6
        // values in [2,6): 5,4,5,5,2,5,3,5
        assertEquals(8, w.rangeCount(0, 12, 2, 6));
        assertEquals(0, w.rangeCount(0, 12, 6, 6));
        assertEquals(0, w.rangeCount(0, 12, 6, 2));
        assertEquals(1, w.rangeCount(0, 12, 6, 7));
        assertEquals(0, w.rangeCount(5, 5, 0, 100));
        assertThrows(IndexOutOfBoundsException.class, () -> w.countLess(0, 13, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> w.countLess(5, 4, 1));
    }

    @Test
    void degenerateInputs() {
        WaveletMatrix empty = new WaveletMatrix(new int[0]);
        assertEquals(0, empty.length());
        assertEquals(0, empty.rank(0, 0));
        assertEquals(0, empty.countLess(0, 0, 5));
        assertThrows(IndexOutOfBoundsException.class, () -> empty.access(0));
        assertThrows(IndexOutOfBoundsException.class, () -> empty.select(0, 0));
        empty.checkInvariants();

        WaveletMatrix zeros = new WaveletMatrix(new int[] {0, 0, 0});
        assertEquals(3, zeros.rank(0, 3));
        assertEquals(2, zeros.select(0, 2));
        assertEquals(0, zeros.kthSmallest(0, 3, 2));
        assertEquals(3, zeros.countLess(0, 3, 1));
        zeros.checkInvariants();

        WaveletMatrix big = new WaveletMatrix(new int[] {Integer.MAX_VALUE, 0, Integer.MAX_VALUE});
        assertEquals(Integer.MAX_VALUE, big.access(2));
        assertEquals(2, big.rank(Integer.MAX_VALUE, 3));
        assertEquals(2, big.select(Integer.MAX_VALUE, 1));
        assertEquals(1, big.countLess(0, 3, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, big.kthSmallest(0, 3, 2));
        big.checkInvariants();

        assertThrows(IllegalArgumentException.class, () -> new WaveletMatrix(new int[] {1, -1}));
        assertThrows(NullPointerException.class, () -> new WaveletMatrix(null));
    }

    @Test
    void constructorDoesNotAliasTheInput() {
        int[] a = {3, 1, 2};
        WaveletMatrix w = new WaveletMatrix(a);
        a[0] = 0;
        assertEquals(3, w.access(0));
    }

    static void differential(int[] a, Random rnd, int queries, String label) {
        WaveletMatrix w = new WaveletMatrix(a);
        int n = a.length;
        for (int i = 0; i < n; i++) assertEquals(a[i], w.access(i), label + " access " + i);
        int max = Arrays.stream(a).max().orElse(0);
        for (int q = 0; q < queries; q++) {
            int v = rnd.nextInt(4) == 0 ? rnd.nextInt(max + 3) : a[rnd.nextInt(n)];
            int i = rnd.nextInt(n + 1);
            int cnt = 0;
            List<Integer> pos = new ArrayList<>();
            for (int j = 0; j < n; j++) if (a[j] == v) pos.add(j);
            for (int j = 0; j < i; j++) if (a[j] == v) cnt++;
            assertEquals(cnt, w.rank(v, i), label + " rank v=" + v + " i=" + i);
            if (!pos.isEmpty()) {
                int k = rnd.nextInt(pos.size());
                assertEquals(pos.get(k), w.select(v, k), label + " select v=" + v + " k=" + k);
            }
            assertThrows(IndexOutOfBoundsException.class, () -> w.select(v, pos.size()));

            int l = rnd.nextInt(n);
            int r = l + 1 + rnd.nextInt(n - l);
            int[] sub = Arrays.copyOfRange(a, l, r);
            Arrays.sort(sub);
            int k = rnd.nextInt(sub.length);
            assertEquals(sub[k], w.kthSmallest(l, r, k), label + " kth l=" + l + " r=" + r + " k=" + k);
            int x = rnd.nextInt(max + 3) - 1;
            int less = 0;
            for (int y : sub) if (y < x) less++;
            assertEquals(less, w.countLess(l, r, x), label + " countLess x=" + x);
            int lo = rnd.nextInt(max + 2);
            int hi = lo + rnd.nextInt(max + 2);
            int inRange = 0;
            for (int y : sub) if (y >= lo && y < hi) inRange++;
            assertEquals(inRange, w.rangeCount(l, r, lo, hi), label + " rangeCount [" + lo + "," + hi + ")");
        }
        w.checkInvariants();
    }

    @Test
    void randomDifferentialAcrossAlphabetSizes() {
        Random rnd = new Random(424242);
        int[] alphabets = {1, 2, 3, 7, 8, 9, 256, 1 << 20};
        for (int alpha : alphabets) {
            for (int round = 0; round < 6; round++) {
                int n = 1 + rnd.nextInt(round < 2 ? 5 : 400);
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = rnd.nextInt(alpha);
                differential(a, rnd, 60, "alpha=" + alpha + " n=" + n);
            }
        }
    }

    @Test
    void skewedAndSortedInputs() {
        Random rnd = new Random(99);
        int[] sorted = new int[300];
        for (int i = 0; i < sorted.length; i++) sorted[i] = i / 3;
        differential(sorted, rnd, 100, "sorted");
        int[] desc = new int[300];
        for (int i = 0; i < desc.length; i++) desc[i] = 299 - i;
        differential(desc, rnd, 100, "desc");
        int[] skew = new int[500];
        for (int i = 0; i < skew.length; i++) skew[i] = rnd.nextInt(10) == 0 ? rnd.nextInt(1000) : 7;
        differential(skew, rnd, 100, "skew");
    }

    @Test
    void sortingByKthSmallestReproducesSortedArray() {
        Random rnd = new Random(5);
        int[] a = new int[257];
        for (int i = 0; i < a.length; i++) a[i] = rnd.nextInt(50);
        WaveletMatrix w = new WaveletMatrix(a);
        int[] s = a.clone();
        Arrays.sort(s);
        for (int k = 0; k < a.length; k++) assertEquals(s[k], w.kthSmallest(0, a.length, k));
    }

    @Test
    void invariantCheckerDetectsCorruption() {
        WaveletMatrix w = new WaveletMatrix(A);
        w.checkInvariants();
        IllegalStateException e1 = assertThrows(IllegalStateException.class,
                () -> WaveletMatrix.corruptZeros(w, 0, 1).checkInvariants());
        assertTrue(e1.getMessage().startsWith("wavelet: zeros"), e1.getMessage());
        IllegalStateException e2 = assertThrows(IllegalStateException.class,
                () -> WaveletMatrix.corruptLength(w, 11).checkInvariants());
        assertTrue(e2.getMessage().startsWith("wavelet: level length"), e2.getMessage());
    }
}
