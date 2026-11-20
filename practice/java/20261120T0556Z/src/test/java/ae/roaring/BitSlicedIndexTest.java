package ae.roaring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class BitSlicedIndexTest {

    static int[] ids(RoaringBitmap b) { return b.toArray(); }

    static int[] brute(int[] vals, IntPredicate p) {
        return java.util.stream.IntStream.range(0, vals.length).filter(i -> p.test(vals[i])).toArray();
    }

    @Test
    void literalFixtureAllOperators() {
        //                 row: 0  1  2  3  4  5
        int[] vals = {5, 0, 12, 5, 7, 12};
        BitSlicedIndex ix = BitSlicedIndex.of(vals);
        assertEquals(6, ix.count());
        assertArrayEquals(new int[] {0, 3}, ids(ix.equalTo(5)));
        assertArrayEquals(new int[] {1}, ids(ix.equalTo(0)));
        assertArrayEquals(new int[] {}, ids(ix.equalTo(6)));
        assertArrayEquals(new int[] {0, 1, 3}, ids(ix.lessThan(7)));
        assertArrayEquals(new int[] {0, 1, 3, 4}, ids(ix.lessOrEqual(7)));
        assertArrayEquals(new int[] {2, 5}, ids(ix.greaterThan(7)));
        assertArrayEquals(new int[] {2, 4, 5}, ids(ix.greaterOrEqual(7)));
        assertArrayEquals(new int[] {0, 3, 4}, ids(ix.between(5, 7)));
        assertArrayEquals(new int[] {}, ids(ix.between(8, 7)));
        assertEquals(41, ix.sum(null));
        RoaringBitmap filter = RoaringBitmap.deserialize(of(0, 2, 4).serialize());
        assertEquals(24, ix.sum(filter));
    }

    static RoaringBitmap of(int... xs) {
        RoaringBitmap b = new RoaringBitmap();
        for (int x : xs) b.add(x);
        return b;
    }

    @Test
    void outOfRangeThresholdsBehaveLikeMathematicalComparisons() {
        int[] vals = {0, 1, Integer.MAX_VALUE, 1000};
        BitSlicedIndex ix = BitSlicedIndex.of(vals);
        assertArrayEquals(new int[] {}, ids(ix.lessThan(0)));
        assertArrayEquals(new int[] {}, ids(ix.lessThan(-5)));
        assertArrayEquals(new int[] {0, 1, 2, 3}, ids(ix.greaterThan(-5)));
        assertArrayEquals(new int[] {0, 1, 2, 3}, ids(ix.greaterOrEqual(Integer.MIN_VALUE)));
        assertArrayEquals(new int[] {}, ids(ix.equalTo(-1)));
        assertArrayEquals(new int[] {2}, ids(ix.equalTo(Integer.MAX_VALUE)));
        assertArrayEquals(new int[] {0, 1, 3}, ids(ix.lessThan(Integer.MAX_VALUE)));
        assertArrayEquals(new int[] {0, 1, 2, 3}, ids(ix.lessOrEqual(Integer.MAX_VALUE)));
        assertArrayEquals(new int[] {}, ids(ix.greaterThan(Integer.MAX_VALUE)));
        assertEquals((long) Integer.MAX_VALUE + 1001, ix.sum(null));
    }

    @Test
    void randomisedAgainstBruteForce() {
        for (int seed = 0; seed < 25; seed++) {
            Random r = new Random(seed);
            int n = 1 + r.nextInt(3000);
            int range = new int[] {2, 10, 300, 70000, Integer.MAX_VALUE}[seed % 5];
            int[] vals = new int[n];
            for (int i = 0; i < n; i++) vals[i] = r.nextInt(range);
            BitSlicedIndex ix = BitSlicedIndex.of(vals);
            RoaringBitmap filter = new RoaringBitmap();
            long expectedSum = 0;
            for (int i = 0; i < n; i++) if (r.nextInt(3) == 0) { filter.add(i); expectedSum += vals[i]; }
            assertEquals(expectedSum, ix.sum(filter), "seed " + seed + " sum");
            for (int q = 0; q < 40; q++) {
                int v = r.nextInt(4) == 0 ? vals[r.nextInt(n)] : r.nextInt(range);
                int w = v + r.nextInt(50);
                String ctx = "seed " + seed + " v=" + v + " w=" + w;
                assertArrayEquals(brute(vals, x -> x == v), ids(ix.equalTo(v)), ctx + " eq");
                assertArrayEquals(brute(vals, x -> x < v), ids(ix.lessThan(v)), ctx + " lt");
                assertArrayEquals(brute(vals, x -> x <= v), ids(ix.lessOrEqual(v)), ctx + " le");
                assertArrayEquals(brute(vals, x -> x > v), ids(ix.greaterThan(v)), ctx + " gt");
                assertArrayEquals(brute(vals, x -> x >= v), ids(ix.greaterOrEqual(v)), ctx + " ge");
                if (w >= v) assertArrayEquals(brute(vals, x -> x >= v && x <= w), ids(ix.between(v, w)), ctx + " between");
            }
        }
    }

    @Test
    void rejectsNegativeValuesAndHandlesEmptyColumn() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BitSlicedIndex.of(new int[] {3, -1}));
        assertTrue(e.getMessage().contains("row 1"), e.getMessage());
        BitSlicedIndex empty = BitSlicedIndex.of(new int[0]);
        assertEquals(0, empty.count());
        assertTrue(empty.lessThan(10).isEmpty());
        assertTrue(empty.greaterOrEqual(0).isEmpty());
        assertEquals(0, empty.sum(null));
    }
}
