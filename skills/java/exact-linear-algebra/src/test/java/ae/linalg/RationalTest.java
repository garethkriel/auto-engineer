package ae.linalg;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class RationalTest {
    @Test
    void normalisesSignAndGcd() {
        Rational r = Rational.of(6, -8);
        assertEquals(BigInteger.valueOf(-3), r.num());
        assertEquals(BigInteger.valueOf(4), r.den());
        assertEquals("-3/4", r.toString());
        assertEquals(Rational.of(-3, 4), r);
        assertEquals(Rational.of(-3, 4).hashCode(), r.hashCode());
        assertEquals("5", Rational.of(10, 2).toString());
        assertEquals(Rational.ZERO, Rational.of(0, -7));
        assertEquals("0", Rational.of(0, -7).toString());
    }

    @Test
    void arithmeticLiterals() {
        Rational a = Rational.of(1, 2);
        Rational b = Rational.of(-2, 3);
        assertEquals(Rational.of(-1, 6), a.add(b));
        assertEquals(Rational.of(7, 6), a.sub(b));
        assertEquals(Rational.of(-1, 3), a.mul(b));
        assertEquals(Rational.of(-3, 4), a.div(b));
        assertEquals(Rational.of(2), a.inverse());
        assertEquals(Rational.of(8, 27), b.pow(3).negate());
        assertEquals(Rational.of(9, 4), b.pow(-2));
        assertEquals(Rational.ONE, b.pow(0));
    }

    @Test
    void divisionByZeroFails() {
        assertThrows(ArithmeticException.class, () -> Rational.of(1, 0));
        assertThrows(ArithmeticException.class, () -> Rational.ONE.div(Rational.ZERO));
        assertThrows(ArithmeticException.class, Rational.ZERO::inverse);
        assertThrows(ArithmeticException.class, () -> Rational.ZERO.pow(-1));
    }

    @Test
    void orderingIsByValueNotByParts() {
        assertTrue(Rational.of(1, 3).compareTo(Rational.of(1, 2)) < 0);
        assertTrue(Rational.of(-1, 3).compareTo(Rational.of(-1, 2)) > 0);
        assertEquals(0, Rational.of(2, 4).compareTo(Rational.of(1, 2)));
        assertEquals(-1, Rational.of(-5, 9).signum());
        assertEquals(0, Rational.ZERO.signum());
    }

    @Test
    void noOverflowWithHugeValues() {
        Rational big = Rational.of(Long.MAX_VALUE).mul(Rational.of(Long.MAX_VALUE));
        assertEquals(new BigInteger("85070591730234615847396907784232501249"), big.num());
        assertEquals(Rational.of(Long.MAX_VALUE), big.div(Rational.of(Long.MAX_VALUE)));
    }
}
