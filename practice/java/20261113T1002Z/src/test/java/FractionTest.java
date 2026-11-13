import static org.junit.jupiter.api.Assertions.*;

import ae.Fraction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FractionTest {
    @Test void reducesOnConstruction() {
        Fraction f = Fraction.of(6, 8);
        assertEquals(3, f.numerator());
        assertEquals(4, f.denominator());
    }

    @Test void normalizesNegativeDenominator() {
        Fraction f = Fraction.of(3, -6);
        assertEquals(-1, f.numerator());
        assertEquals(2, f.denominator());
        assertEquals(Fraction.of(1, 2), Fraction.of(-1, -2));
    }

    @Test void zeroIsCanonical() {
        assertEquals(Fraction.ZERO, Fraction.of(0, -7));
        assertEquals(1, Fraction.of(0, 5).denominator());
    }

    @Test void zeroDenominatorRejected() {
        assertThrows(IllegalArgumentException.class, () -> Fraction.of(1, 0));
    }

    @Test void minValueDenominatorNegationOverflows() {
        assertThrows(ArithmeticException.class, () -> Fraction.of(1, Long.MIN_VALUE));
    }

    @Test void equalsAndHashCodeAgreeForUnreducedInputs() {
        Fraction a = Fraction.of(1, 2);
        Fraction b = Fraction.of(50, 100);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        Set<Fraction> set = new HashSet<>(List.of(a));
        assertTrue(set.contains(b));
        assertNotEquals(a, Fraction.of(1, 3));
        assertNotEquals(a, null);
        assertNotEquals(a, "1/2");
    }

    @Test void toStringFormats() {
        assertEquals("3/4", Fraction.of(6, 8).toString());
        assertEquals("-1/2", Fraction.of(1, -2).toString());
        assertEquals("5", Fraction.of(10, 2).toString());
        assertEquals("0", Fraction.ZERO.toString());
    }

    @Test void arithmetic() {
        Fraction half = Fraction.of(1, 2), third = Fraction.of(1, 3);
        assertEquals(Fraction.of(5, 6), half.plus(third));
        assertEquals(Fraction.of(1, 6), half.minus(third));
        assertEquals(Fraction.of(1, 6), half.times(third));
        assertEquals(Fraction.of(3, 2), half.dividedBy(third));
        assertEquals(Fraction.ONE, half.plus(half));
        assertEquals(Fraction.ZERO, half.minus(half));
    }

    @Test void divideByZeroThrows() {
        assertThrows(ArithmeticException.class, () -> Fraction.ONE.dividedBy(Fraction.ZERO));
    }

    @Test void overflowThrows() {
        Fraction big = Fraction.of(Long.MAX_VALUE, 1);
        assertThrows(ArithmeticException.class, () -> big.plus(Fraction.ONE));
        assertThrows(ArithmeticException.class, () -> big.times(big));
    }

    @Test void compareToOrdersCorrectly() {
        List<Fraction> l = new ArrayList<>(List.of(Fraction.of(1, 2), Fraction.of(-3, 4), Fraction.ONE, Fraction.of(1, 3)));
        Collections.sort(l);
        assertEquals(List.of(Fraction.of(-3, 4), Fraction.of(1, 3), Fraction.of(1, 2), Fraction.ONE), l);
        assertEquals(0, Fraction.of(2, 4).compareTo(Fraction.of(1, 2)));
    }

    @Test void compareToDoesNotOverflow() {
        Fraction a = Fraction.of(Long.MAX_VALUE, Long.MAX_VALUE - 1);
        Fraction b = Fraction.of(Long.MAX_VALUE - 1, Long.MAX_VALUE - 2);
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
    }

    @Test void parseValid() {
        assertEquals(Fraction.of(1, 2), Fraction.parse(" 2/4 "));
        assertEquals(Fraction.of(-7, 1), Fraction.parse("-7"));
        assertEquals(Fraction.of(-1, 2), Fraction.parse("1/-2"));
    }

    @Test void parseInvalid() {
        for (String s : new String[] {"", "a/b", "1/", "/2", "1/2/3", "1/0", "1.5", " "}) {
            assertThrows(IllegalArgumentException.class, () -> Fraction.parse(s), s);
        }
        assertThrows(NullPointerException.class, () -> Fraction.parse(null));
    }
}
