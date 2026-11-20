package ae.regex;

import static org.junit.jupiter.api.Assertions.*;

import java.util.BitSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CharSetTest {

    @Test
    void rangesAreNormalisedAndMerged() {
        CharSet s = CharSet.of('a').union(CharSet.of('c')).union(CharSet.of('b'));
        assertEquals(CharSet.range('a', 'c'), s);
        assertEquals(1, s.rangeCount());
        assertEquals(3, s.size());
    }

    @Test
    void negateOfEmptyIsAnyAndBack() {
        assertEquals(CharSet.ANY, CharSet.EMPTY.negate());
        assertEquals(CharSet.EMPTY, CharSet.ANY.negate());
        assertEquals(65536, CharSet.ANY.size());
    }

    @Test
    void negateGapsAreExact() {
        CharSet s = CharSet.range(0, 9).union(CharSet.range(20, 30)).union(CharSet.of(0xFFFF));
        CharSet n = s.negate();
        assertEquals(2, n.rangeCount());
        assertTrue(n.contains(10) && n.contains(19) && n.contains(31) && n.contains(0xFFFE));
        assertFalse(n.contains(9) || n.contains(20) || n.contains(30) || n.contains(0xFFFF));
    }

    @Test
    void containsUsesBinarySearchCorrectlyAtBoundaries() {
        CharSet s = CharSet.range('b', 'd').union(CharSet.range('x', 'z'));
        assertFalse(s.contains('a'));
        assertTrue(s.contains('b') && s.contains('d'));
        assertFalse(s.contains('e') || s.contains('w'));
        assertTrue(s.contains('x') && s.contains('z'));
        assertFalse(s.contains('{'));
    }

    @Test
    void invalidRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> CharSet.range('b', 'a'));
        assertThrows(IllegalArgumentException.class, () -> CharSet.range(-1, 3));
        assertThrows(IllegalArgumentException.class, () -> CharSet.range(0, 0x10000));
    }

    @Test
    void randomisedAlgebraAgreesWithBitSetModel() {
        Random rnd = new Random(7);
        for (int round = 0; round < 300; round++) {
            BitSet ma = new BitSet(256);
            BitSet mb = new BitSet(256);
            CharSet a = randomSet(rnd, ma);
            CharSet b = randomSet(rnd, mb);
            BitSet and = (BitSet) ma.clone();
            and.and(mb);
            BitSet or = (BitSet) ma.clone();
            or.or(mb);
            BitSet minus = (BitSet) ma.clone();
            minus.andNot(mb);
            check(and, a.intersect(b));
            check(or, a.union(b));
            check(minus, a.minus(b));
            assertEquals(a, a.negate().negate());
            assertEquals(CharSet.ANY, a.union(a.negate()));
            assertEquals(CharSet.EMPTY, a.intersect(a.negate()));
            assertEquals(a.hashCode(), a.union(a).hashCode());
        }
    }

    private static CharSet randomSet(Random rnd, BitSet model) {
        CharSet s = CharSet.EMPTY;
        int parts = rnd.nextInt(5);
        for (int i = 0; i < parts; i++) {
            int lo = rnd.nextInt(256);
            int hi = Math.min(255, lo + rnd.nextInt(30));
            s = s.union(CharSet.range(lo, hi));
            model.set(lo, hi + 1);
        }
        return s;
    }

    /** Compare within 0..255 and assert nothing above 255 leaked in (the model never sets bits there). */
    private static void check(BitSet model, CharSet actual) {
        for (int c = 0; c < 256; c++) {
            assertEquals(model.get(c), actual.contains(c), "char " + c);
        }
        assertEquals(model.cardinality(), actual.size());
    }
}
