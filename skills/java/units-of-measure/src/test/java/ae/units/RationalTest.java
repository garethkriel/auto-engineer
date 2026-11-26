package ae.units;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RationalTest {

    @Test
    void normalisesSignAndCommonFactors() {
        assertEquals("1/2", Rational.of(2, 4).toString());
        assertEquals("-1/2", Rational.of(3, -6).toString());
        assertEquals("1/2", Rational.of(-3, -6).toString());
        assertEquals("0", Rational.of(0, -7).toString());
        assertEquals("5", Rational.of(10, 2).toString());
        assertEquals(Rational.of(1, 3), Rational.of(-2, -6));
        assertEquals(Rational.of(1, 3).hashCode(), Rational.of(-2, -6).hashCode());
    }

    @Test
    void zeroDenominatorIsRejected() {
        assertThrows(ArithmeticException.class, () -> Rational.of(1, 0));
    }

    @Test
    void arithmeticLiterals() {
        Rational a = Rational.of(1, 3);
        Rational b = Rational.of(1, 6);
        assertEquals(Rational.of(1, 2), a.plus(b));
        assertEquals(Rational.of(1, 6), a.minus(b));
        assertEquals(Rational.of(1, 18), a.times(b));
        assertEquals(Rational.of(2, 1), a.dividedBy(b));
        assertEquals(Rational.of(-1, 3), a.negate());
        assertEquals(1, a.signum());
        assertEquals(-1, a.negate().signum());
        assertEquals(0, Rational.of(0).signum());
    }

    @Test
    void divisionByZeroThrows() {
        assertThrows(ArithmeticException.class, () -> Rational.of(1).dividedBy(Rational.of(0)));
    }

    @Test
    void powerLiterals() {
        assertEquals(Rational.of(8, 27), Rational.of(2, 3).pow(3));
        assertEquals(Rational.of(27, 8), Rational.of(2, 3).pow(-3));
        assertEquals(Rational.of(1), Rational.of(-5, 7).pow(0));
        assertEquals(Rational.of(-8), Rational.of(-2).pow(3));
        assertEquals(Rational.of(1, 4), Rational.of(-2).pow(-2));
        assertThrows(ArithmeticException.class, () -> Rational.of(0).pow(-1));
        assertEquals(Rational.of(0), Rational.of(0).pow(5));
        assertEquals(Rational.of(1), Rational.of(0).pow(0));
    }

    @Test
    void hugePowersAreRefusedBeforeAllocation() {
        // 3 needs 2 bits, so 3^600000 would need 1.2M bits: over the 2^20-bit budget
        assertThrows(ArithmeticException.class, () -> Rational.of(3).pow(600000));
        assertThrows(ArithmeticException.class, () -> Rational.of(3).pow(-600000));
        assertThrows(ArithmeticException.class, () -> Rational.of(1).pow(Integer.MIN_VALUE));
        // control: within the budget the exact value is produced
        assertEquals(BigInteger.valueOf(3).pow(100000), Rational.of(3).pow(100000).numerator());
    }

    @Test
    void compareToOrdersByValueNotByParts() {
        // 1/3 < 2/5 although 1 < 2 and 3 < 5 both hold; -1/2 < -1/3 reverses the parts order.
        assertTrue(Rational.of(1, 3).compareTo(Rational.of(2, 5)) < 0);
        assertTrue(Rational.of(-1, 2).compareTo(Rational.of(-1, 3)) < 0);
        assertTrue(Rational.of(7, 2).compareTo(Rational.of(10, 3)) > 0);
        assertEquals(0, Rational.of(2, 4).compareTo(Rational.of(1, 2)));
    }

    @Test
    void parseDecimalLiterals() {
        assertEquals(Rational.of(25, 2), Rational.parseDecimal("12.5"));
        assertEquals(Rational.of(300), Rational.parseDecimal("3e2"));
        assertEquals(Rational.of(3, 2000), Rational.parseDecimal("1.5E-3"));
        assertEquals(Rational.of(1, 2), Rational.parseDecimal(".5"));
        assertEquals(Rational.of(0), Rational.parseDecimal("-0.0"));
        assertEquals(Rational.of(1), Rational.parseDecimal("+1"));
        assertEquals(Rational.of(-7, 4), Rational.parseDecimal("-1.75"));
        assertEquals(Rational.of(1, 1000), Rational.parseDecimal("0.001"));
    }

    @Test
    void parseDecimalRejectsGarbageAndHugeExponents() {
        for (String bad : new String[] {"", "abc", "1e", "1.2.3", " 1", "0x10"}) {
            assertThrows(NumberFormatException.class, () -> Rational.parseDecimal(bad), bad);
        }
        // would allocate a 10^999999999 BigInteger without the exponent cap
        assertThrows(IllegalArgumentException.class, () -> Rational.parseDecimal("1e999999999"));
        assertThrows(IllegalArgumentException.class, () -> Rational.parseDecimal("1e-999999999"));
        // control: exactly at the cap is accepted
        assertEquals(BigInteger.TEN.pow(1000), Rational.parseDecimal("1e1000").numerator());
    }

    @Test
    void doubleValueIsCloseAndHandlesHugeParts() {
        assertEquals(0.5, Rational.of(1, 2).doubleValue(), 0.0);
        assertEquals(1.0 / 3, Rational.of(1, 3).doubleValue(), 1e-16);
        assertEquals(-2.5, Rational.of(-5, 2).doubleValue(), 0.0);
        Rational big = Rational.of(BigInteger.TEN.pow(400).add(BigInteger.ONE), BigInteger.TEN.pow(399));
        assertEquals(10.0, big.doubleValue(), 1e-9);
    }

    @Test
    void randomDifferentialAgainstBigDecimal() {
        Random rnd = new Random(20261126);
        for (int i = 0; i < 400; i++) {
            BigDecimal a = BigDecimal.valueOf(rnd.nextInt(2_000_001) - 1_000_000, rnd.nextInt(7));
            BigDecimal b = BigDecimal.valueOf(rnd.nextInt(2_000_001) - 1_000_000, rnd.nextInt(7));
            Rational ra = Rational.parseDecimal(a.toPlainString());
            Rational rb = Rational.parseDecimal(b.toPlainString());
            String ctx = a + " , " + b;
            assertEquals(Rational.parseDecimal(a.add(b).toPlainString()), ra.plus(rb), "plus " + ctx);
            assertEquals(Rational.parseDecimal(a.subtract(b).toPlainString()), ra.minus(rb), "minus " + ctx);
            assertEquals(Rational.parseDecimal(a.multiply(b).toPlainString()), ra.times(rb), "times " + ctx);
            assertEquals(Integer.signum(a.compareTo(b)), Integer.signum(ra.compareTo(rb)), "cmp " + ctx);
            if (b.signum() != 0) {
                assertEquals(ra, ra.dividedBy(rb).times(rb), "div " + ctx);
            }
        }
    }
}
