package ae.units;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class DimensionTest {

    // index order: 0 L, 1 M, 2 T, 3 I, 4 Th, 5 N, 6 J
    private static Dimension random(Random rnd) {
        int[] e = new int[7];
        for (int i = 0; i < 7; i++) {
            e[i] = rnd.nextInt(7) - 3;
        }
        return Dimension.of(e);
    }

    @Test
    void describeLiterals() {
        assertEquals("1", Dimension.NONE.describe());
        assertEquals("L", Dimension.LENGTH.describe());
        assertEquals("M", Dimension.MASS.describe());
        assertEquals("T", Dimension.TIME.describe());
        assertEquals("I", Dimension.CURRENT.describe());
        assertEquals("Th", Dimension.TEMPERATURE.describe());
        assertEquals("N", Dimension.AMOUNT.describe());
        assertEquals("J", Dimension.LUMINOUS.describe());
        Dimension force = Dimension.MASS.times(Dimension.LENGTH).dividedBy(Dimension.TIME.pow(2));
        assertEquals("L M T^-2", force.describe());
        assertEquals("L^2 M T^-2", force.times(Dimension.LENGTH).describe());
        assertEquals("L^-1", Dimension.LENGTH.pow(-1).describe());
    }

    @Test
    void ofRequiresExactlySevenExponents() {
        assertThrows(IllegalArgumentException.class, () -> Dimension.of(1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> Dimension.of(0, 0, 0, 0, 0, 0, 0, 0));
        assertEquals(Dimension.LENGTH, Dimension.of(1, 0, 0, 0, 0, 0, 0));
        assertEquals(Dimension.LUMINOUS, Dimension.of(0, 0, 0, 0, 0, 0, 1));
    }

    @Test
    void ofCopiesItsArgument() {
        int[] e = {1, 0, 0, 0, 0, 0, 0};
        Dimension d = Dimension.of(e);
        e[0] = 5;
        assertEquals(1, d.exponent(0));
    }

    @Test
    void exponentIndexOutOfRangeThrows() {
        assertThrows(IndexOutOfBoundsException.class, () -> Dimension.LENGTH.exponent(7));
        assertThrows(IndexOutOfBoundsException.class, () -> Dimension.LENGTH.exponent(-1));
    }

    @Test
    void isDimensionlessOnlyForAllZero() {
        assertTrue(Dimension.NONE.isDimensionless());
        assertTrue(Dimension.LENGTH.dividedBy(Dimension.LENGTH).isDimensionless());
        assertTrue(Dimension.LENGTH.pow(0).isDimensionless());
        assertFalse(Dimension.LUMINOUS.isDimensionless());
    }

    @Test
    void algebraProperties() {
        Random rnd = new Random(7);
        for (int i = 0; i < 300; i++) {
            Dimension a = random(rnd);
            Dimension b = random(rnd);
            Dimension c = random(rnd);
            int n = rnd.nextInt(9) - 4;
            assertEquals(a.times(b), b.times(a));
            assertEquals(a.times(b).times(c), a.times(b.times(c)));
            assertEquals(a, a.times(b).dividedBy(b));
            assertEquals(a, a.times(Dimension.NONE));
            assertTrue(a.dividedBy(a).isDimensionless());
            assertEquals(a.times(a).times(a), a.pow(3));
            assertEquals(a.pow(-1), Dimension.NONE.dividedBy(a));
            for (int k = 0; k < 7; k++) {
                assertEquals(n * a.exponent(k), a.pow(n).exponent(k));
                assertEquals(a.exponent(k) + b.exponent(k), a.times(b).exponent(k));
            }
            boolean same = true;
            for (int k = 0; k < 7; k++) {
                same &= a.exponent(k) == b.exponent(k);
            }
            assertEquals(same, a.equals(b));
            if (same) {
                assertEquals(a.hashCode(), b.hashCode());
            }
        }
    }

    @Test
    void differentDimensionsAreNotEqual() {
        assertNotEquals(Dimension.LENGTH, Dimension.MASS);
        assertNotEquals(Dimension.LENGTH, Dimension.LENGTH.pow(2));
        assertNotEquals(Dimension.LENGTH, null);
        assertNotEquals(Dimension.LENGTH, "L");
    }

    @Test
    void overflowThrowsInsteadOfWrapping() {
        Dimension big = Dimension.LENGTH.pow(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, big.exponent(0));
        assertThrows(ArithmeticException.class, () -> big.times(Dimension.LENGTH));
        assertThrows(ArithmeticException.class, () -> big.pow(2));
        assertThrows(ArithmeticException.class, () -> Dimension.LENGTH.pow(Integer.MIN_VALUE).dividedBy(Dimension.LENGTH));
        // controls: the largest representable results still work
        assertEquals(Integer.MAX_VALUE - 1, big.dividedBy(Dimension.LENGTH).exponent(0));
        assertEquals(Integer.MAX_VALUE, big.pow(1).exponent(0));
    }
}
