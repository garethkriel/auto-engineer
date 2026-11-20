package ae.lp;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class RationalTest {
    @Test
    void normalisesSignAndGcd() {
        Rational r = Rational.of(6, -8);
        assertEquals("-3/4", r.toString());
        assertEquals(Rational.of(-3, 4), r);
        assertEquals(Rational.of(-3, 4).hashCode(), r.hashCode());
    }

    @Test
    void integersPrintWithoutDenominator() {
        assertEquals("5", Rational.of(10, 2).toString());
        assertEquals("0", Rational.of(0, -7).toString());
    }

    @Test
    void arithmetic() {
        Rational a = Rational.of(1, 3);
        Rational b = Rational.of(1, 6);
        assertEquals(Rational.of(1, 2), a.add(b));
        assertEquals(Rational.of(1, 6), a.sub(b));
        assertEquals(Rational.of(1, 18), a.mul(b));
        assertEquals(Rational.of(2, 1), a.div(b));
        assertEquals(Rational.of(-1, 3), a.negate());
    }

    @Test
    void divisionByZeroRejected() {
        assertThrows(ArithmeticException.class, () -> Rational.ONE.div(Rational.ZERO));
        assertThrows(ArithmeticException.class, () -> Rational.of(1, 0));
    }

    @Test
    void comparisonAndSign() {
        assertTrue(Rational.of(1, 3).compareTo(Rational.of(1, 2)) < 0);
        assertTrue(Rational.of(-1, 2).compareTo(Rational.of(-1, 3)) < 0);
        assertEquals(0, Rational.of(2, 4).compareTo(Rational.of(1, 2)));
        assertEquals(-1, Rational.of(-5, 3).signum());
        assertEquals(0, Rational.ZERO.signum());
        assertEquals(1, Rational.of(5, 3).signum());
    }

    @Test
    void parse() {
        assertEquals(Rational.of(7), Rational.parse("7"));
        assertEquals(Rational.of(-7), Rational.parse("-7"));
        assertEquals(Rational.of(1, 4), Rational.parse("1/4"));
        assertEquals(Rational.of(-1, 4), Rational.parse("-1/4"));
        assertThrows(NumberFormatException.class, () -> Rational.parse("1.5"));
        assertThrows(NumberFormatException.class, () -> Rational.parse(""));
        assertThrows(NumberFormatException.class, () -> Rational.parse("1/"));
        assertThrows(ArithmeticException.class, () -> Rational.parse("1/0"));
    }

    @Test
    void hugeValuesDoNotOverflow() {
        Rational big = Rational.of(new BigInteger("100000000000000000000"), BigInteger.ONE);
        assertEquals("10000000000000000000000000000000000000000", big.mul(big).toString());
        assertEquals(Rational.ONE, big.div(big));
    }

    @Test
    void dotProduct() {
        Rational[] a = {Rational.of(1, 2), Rational.of(3), Rational.ZERO};
        Rational[] b = {Rational.of(4), Rational.of(-1, 3), Rational.of(99)};
        assertEquals(Rational.ONE, Rational.dot(a, b));
        assertThrows(IllegalArgumentException.class, () -> Rational.dot(a, new Rational[2]));
    }
}
