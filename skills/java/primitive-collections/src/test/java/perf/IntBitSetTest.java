package perf;

import static org.junit.jupiter.api.Assertions.*;

import java.util.BitSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IntBitSetTest {
    @Test
    void setGetClearFlipAcrossWordBoundaries() {
        IntBitSet s = new IntBitSet(130);
        for (int i : new int[] {0, 63, 64, 127, 128, 129}) {
            assertFalse(s.get(i));
            s.set(i);
            assertTrue(s.get(i));
        }
        assertEquals(6, s.cardinality());
        s.clear(64);
        s.flip(63);
        s.flip(1);
        assertFalse(s.get(64));
        assertFalse(s.get(63));
        assertTrue(s.get(1));
        assertEquals(5, s.cardinality()); // 6 set, -64, -63 (flip), +1 (flip)
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new IntBitSet(-1));
        IntBitSet s = new IntBitSet(10);
        assertThrows(IndexOutOfBoundsException.class, () -> s.set(10));
        assertThrows(IndexOutOfBoundsException.class, () -> s.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> s.rank(11));
        assertThrows(IndexOutOfBoundsException.class, () -> s.nextSetBit(-1));
        assertThrows(IllegalArgumentException.class, () -> s.select(-1));
        assertThrows(IllegalArgumentException.class, () -> s.and(new IntBitSet(11)));
    }

    @Test
    void zeroSizeSetBehaves() {
        IntBitSet s = new IntBitSet(0);
        assertEquals(0, s.cardinality());
        assertEquals(-1, s.nextSetBit(0));
        assertEquals(0, s.rank(0));
        assertEquals(-1, s.select(0));
    }

    @Test
    void rankSelectNextOnKnownFixture() {
        IntBitSet s = new IntBitSet(200);
        int[] bits = {3, 64, 65, 100, 199};
        for (int b : bits) {
            s.set(b);
        }
        assertEquals(0, s.rank(3));
        assertEquals(1, s.rank(4));
        assertEquals(1, s.rank(64));
        assertEquals(2, s.rank(65));
        assertEquals(5, s.rank(200));
        for (int k = 0; k < bits.length; k++) {
            assertEquals(bits[k], s.select(k));
        }
        assertEquals(-1, s.select(5));
        assertEquals(3, s.nextSetBit(0));
        assertEquals(64, s.nextSetBit(4));
        assertEquals(64, s.nextSetBit(64));
        assertEquals(199, s.nextSetBit(101));
        assertEquals(-1, s.nextSetBit(200));
    }

    @Test
    void booleanOperationsMatchTruthTable() {
        IntBitSet a = new IntBitSet(4);
        IntBitSet b = new IntBitSet(4);
        a.set(0);
        a.set(1);
        b.set(1);
        b.set(2);
        IntBitSet and = copy(a).and(b);
        IntBitSet or = copy(a).or(b);
        IntBitSet diff = copy(a).andNot(b);
        assertEquals(1, and.cardinality());
        assertTrue(and.get(1));
        assertEquals(3, or.cardinality());
        assertEquals(1, diff.cardinality());
        assertTrue(diff.get(0));
    }

    @Test
    void equalityByContents() {
        IntBitSet a = new IntBitSet(70);
        IntBitSet b = new IntBitSet(70);
        a.set(69);
        assertNotEquals(a, b);
        b.set(69);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new IntBitSet(71));
    }

    @Test
    void randomisedDifferentialAgainstJavaUtilBitSet() {
        for (int seed = 0; seed < 30; seed++) {
            Random rnd = new Random(seed);
            int size = rnd.nextInt(400);
            IntBitSet mine = new IntBitSet(size);
            BitSet ref = new BitSet(size);
            if (size > 0) {
                for (int op = 0; op < 500; op++) {
                    int i = rnd.nextInt(size);
                    switch (rnd.nextInt(3)) {
                        case 0 -> { mine.set(i); ref.set(i); }
                        case 1 -> { mine.clear(i); ref.clear(i); }
                        default -> { mine.flip(i); ref.flip(i); }
                    }
                }
            }
            assertEquals(ref.cardinality(), mine.cardinality(), "seed " + seed);
            for (int i = 0; i <= size; i++) {
                assertEquals(ref.get(0, i).cardinality(), mine.rank(i), "rank " + i + " seed " + seed);
                int expectedNext = i < size ? ref.nextSetBit(i) : -1;
                assertEquals(expectedNext, mine.nextSetBit(i), "next " + i + " seed " + seed);
            }
            int k = 0;
            for (int b = ref.nextSetBit(0); b >= 0; b = ref.nextSetBit(b + 1)) {
                assertEquals(b, mine.select(k++), "select seed " + seed);
            }
            assertEquals(-1, mine.select(k));
        }
    }

    @Test
    void largeSetStaysFast() {
        int n = 50_000_000;
        IntBitSet s = new IntBitSet(n);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            for (int i = 0; i < n; i += 3) {
                s.set(i);
            }
            assertEquals((n + 2) / 3, s.cardinality());
            assertEquals(3 * 1000, s.select(1000));
            int count = 0;
            for (int b = s.nextSetBit(0); b >= 0; b = s.nextSetBit(b + 1)) {
                count++;
            }
            assertEquals((n + 2) / 3, count);
        });
    }

    private static IntBitSet copy(IntBitSet s) {
        return new IntBitSet(s.size()).or(s);
    }
}
