package ae.dec;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DecTest {

    private static Dec d(String s) {
        return Dec.parse(s);
    }

    @Test
    void parseKeepsScale() {
        Dec x = d("1.50");
        assertEquals(BigInteger.valueOf(150), x.unscaled());
        assertEquals(2, x.scale());
    }

    @Test
    void parseExponentLowersScale() {
        Dec x = d("1.5e3"); // 15 * 10^2 -> unscaled 15, scale -2
        assertEquals(BigInteger.valueOf(15), x.unscaled());
        assertEquals(-2, x.scale());
        assertEquals("1.5E+3", x.toString());
        assertEquals("1500", x.toPlainString());
    }

    @Test
    void parseAcceptsLeadingAndTrailingPoint() {
        assertEquals(1, d(".5").scale());
        assertEquals(BigInteger.valueOf(5), d(".5").unscaled());
        assertEquals(0, d("1.").scale());
        assertEquals(BigInteger.valueOf(-5), d("-.5e-3").unscaled());
        assertEquals(4, d("-.5e-3").scale());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1e", "1.2.3", "--1", " 1", "1 ", "e5", ".", "+", "1e+", "1e99999999999", "1e-2147483648x"})
    void parseRejects(String bad) {
        assertThrows(NumberFormatException.class, () -> Dec.parse(bad));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e5", "+1", "-0", "0.", "00012", "1E-5", "-.5e-3"})
    void parseControlsLoad(String ok) {
        assertDoesNotThrow(() -> Dec.parse(ok));
    }

    @ParameterizedTest
    @CsvSource({
        "0.0000001, 1E-7",
        "0.000001, 0.000001",
        "123.45, 123.45",
        "-123.45, -123.45",
        "12345e-8, 0.00012345",
        "12345e-12, 1.2345E-8",
        "5e0, 5",
        "5e2, 5E+2",
        "0, 0",
        "0.00, 0.00",
        "0e3, 0E+3",
        "100, 100"
    })
    void toStringRules(String in, String expected) {
        assertEquals(expected, d(in).toString());
    }

    @ParameterizedTest
    @CsvSource({"1E+3, 1000", "1.5E-3, 0.0015", "-2E+1, -20", "0E+3, 0", "0.00, 0.00", "-0.5, -0.5", "1E-7, 0.0000001"})
    void toPlainStringRules(String in, String expected) {
        assertEquals(expected, d(in).toPlainString());
    }

    @Test
    void addAlignsToLargerScale() {
        Dec s = d("1.10").add(d("2.205"));
        assertEquals("3.305", s.toString());
        assertEquals(3, s.scale());
    }

    @Test
    void subtractAndNegate() {
        assertEquals("-1.095", d("1.10").subtract(d("2.195")).toString());
        assertEquals("-1.10", d("1.10").negate().toString());
        assertEquals("1.10", d("-1.10").abs().toString());
    }

    @Test
    void multiplyAddsScales() {
        Dec p = d("1.5").multiply(d("0.20"));
        assertEquals("0.300", p.toString());
        assertEquals(3, p.scale());
    }

    @Test
    void powExactAndZeroExponent() {
        assertEquals("1.331", d("1.1").pow(3).toString());
        Dec one = d("7.5").pow(0);
        assertEquals("1", one.toString());
        assertEquals(0, one.scale());
        assertThrows(IllegalArgumentException.class, () -> d("2").pow(-1));
    }

    @Test
    void equalsDistinguishesScaleCompareDoesNot() {
        assertNotEquals(d("2.0"), d("2.00"));
        assertEquals(0, d("2.0").compareTo(d("2.00")));
        assertEquals(d("2.0").hashCode(), d("2.0").hashCode());
        assertTrue(d("-1").compareTo(d("-0.5")) < 0);
        assertTrue(d("1E+3").compareTo(d("999.99")) > 0);
    }

    @Test
    void precisionCountsDigits() {
        assertEquals(1, d("0.000").precision());
        assertEquals(5, d("123.45").precision());
        assertEquals(1, d("1E+10").precision());
    }

    // Rows follow the java.math.RoundingMode table: input, UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN.
    @ParameterizedTest
    @CsvSource({
        "5.5,  6, 5, 6, 5, 6, 5, 6",
        "2.5,  3, 2, 3, 2, 3, 2, 2",
        "1.6,  2, 1, 2, 1, 2, 2, 2",
        "1.1,  2, 1, 2, 1, 1, 1, 1",
        "1.0,  1, 1, 1, 1, 1, 1, 1",
        "-1.0, -1, -1, -1, -1, -1, -1, -1",
        "-1.1, -2, -1, -1, -2, -1, -1, -1",
        "-1.6, -2, -1, -1, -2, -2, -2, -2",
        "-2.5, -3, -2, -2, -3, -3, -2, -2",
        "-5.5, -6, -5, -5, -6, -6, -5, -6"
    })
    void setScaleZeroMatchesTable(String in, long up, long down, long ceil, long floor, long hu, long hd, long he) {
        long[] want = {up, down, ceil, floor, hu, hd, he};
        RoundingMode[] modes = {RoundingMode.UP, RoundingMode.DOWN, RoundingMode.CEILING, RoundingMode.FLOOR,
            RoundingMode.HALF_UP, RoundingMode.HALF_DOWN, RoundingMode.HALF_EVEN};
        for (int i = 0; i < modes.length; i++) {
            Dec r = d(in).setScale(0, modes[i]);
            assertEquals(Long.toString(want[i]), r.toString(), in + " " + modes[i]);
            assertEquals(0, r.scale());
        }
    }

    @Test
    void setScaleUnnecessary() {
        assertThrows(ArithmeticException.class, () -> d("1.1").setScale(0, RoundingMode.UNNECESSARY));
        assertEquals("1", d("1.0").setScale(0, RoundingMode.UNNECESSARY).toString());
        assertEquals("1.1000", d("1.1").setScale(4, RoundingMode.UNNECESSARY).toString());
        assertEquals("1E+1", d("12").setScale(-1, RoundingMode.DOWN).toString());
    }

    @Test
    void roundKeepsPrecisionDigitsAndHandlesCarry() {
        assertEquals("10", d("9.99").round(2, RoundingMode.HALF_UP).toString());
        assertEquals(2, d("9.99").round(2, RoundingMode.HALF_UP).precision());
        assertEquals("1.0E+3", d("999.5").round(2, RoundingMode.UP).toString());
        assertEquals("9.9", d("9.99").round(2, RoundingMode.DOWN).toString());
        assertEquals("123.45", d("123.45").round(0, RoundingMode.DOWN).toString());
        assertEquals("123.45", d("123.45").round(9, RoundingMode.DOWN).toString());
        assertEquals("-1.2E+2", d("-123.45").round(2, RoundingMode.HALF_EVEN).toString());
    }

    @Test
    void divideInexactFullPrecision() {
        assertEquals("0.33333", d("1").divide(d("3"), 5, RoundingMode.HALF_UP).toString());
        assertEquals("0.667", d("2").divide(d("3"), 3, RoundingMode.HALF_UP).toString());
        assertEquals("0.666", d("2").divide(d("3"), 3, RoundingMode.DOWN).toString());
        assertEquals("-0.667", d("-2").divide(d("3"), 3, RoundingMode.UP).toString());
    }

    @Test
    void divideExactStripsToPreferredScale() {
        assertEquals("0.25", d("1").divide(d("4"), 10, RoundingMode.HALF_UP).toString());
        assertEquals("2.5", d("10").divide(d("4"), 10, RoundingMode.HALF_UP).toString());
        assertEquals("25", d("100").divide(d("4"), 10, RoundingMode.HALF_UP).toString());
        assertEquals("0.25", d("1.00").divide(d("4"), 10, RoundingMode.HALF_UP).toString());
        assertEquals("0.125", d("1.000").divide(d("8"), 10, RoundingMode.HALF_UP).toString());
        assertEquals("3", d("6").divide(d("2.0"), 10, RoundingMode.HALF_UP).toString());
        assertEquals("1E+2", d("1E+3").divide(d("1E+1"), 10, RoundingMode.HALF_UP).toString());
    }

    @Test
    void divideZeroCases() {
        assertThrows(ArithmeticException.class, () -> d("1").divide(d("0"), 5, RoundingMode.HALF_UP));
        assertThrows(ArithmeticException.class, () -> d("0").divide(d("0.0"), 5, RoundingMode.HALF_UP));
        Dec z = d("0.00").divide(d("3"), 5, RoundingMode.HALF_UP);
        assertEquals(0, z.signum());
        assertEquals(2, z.scale());
        assertThrows(IllegalArgumentException.class, () -> d("1").divide(d("3"), 0, RoundingMode.HALF_UP));
    }

    @Test
    void divideUnnecessary() {
        assertEquals("0.25", d("1").divide(d("4"), 5, RoundingMode.UNNECESSARY).toString());
        assertThrows(ArithmeticException.class, () -> d("1").divide(d("3"), 5, RoundingMode.UNNECESSARY));
    }

    @Test
    void divideExactMethod() {
        assertEquals("0.125", d("1").divideExact(d("8")).toString());
        assertEquals("0.50", d("1.00").divideExact(d("2")).toString());
        assertEquals("1E+2", d("1E+3").divideExact(d("1E+1")).toString());
        assertThrows(ArithmeticException.class, () -> d("1").divideExact(d("3")));
        assertThrows(ArithmeticException.class, () -> d("1").divideExact(d("0")));
    }

    @Test
    void sqrtLiterals() {
        assertEquals("1.414213562", d("2").sqrt(10, RoundingMode.HALF_EVEN).toString());
        assertEquals("1.414213563", d("2").sqrt(10, RoundingMode.UP).toString());
        assertEquals("1.414213562", d("2").sqrt(10, RoundingMode.DOWN).toString());
        assertEquals("2", d("4").sqrt(10, RoundingMode.HALF_EVEN).toString());
        assertEquals("0.5", d("0.25").sqrt(10, RoundingMode.HALF_EVEN).toString());
        assertEquals("4", d("16").sqrt(5, RoundingMode.HALF_EVEN).toString());
        assertEquals(0, d("0.00").sqrt(5, RoundingMode.HALF_EVEN).signum());
        assertThrows(ArithmeticException.class, () -> d("-1").sqrt(5, RoundingMode.HALF_EVEN));
        assertThrows(ArithmeticException.class, () -> d("2").sqrt(5, RoundingMode.UNNECESSARY));
        assertEquals("3", d("9").sqrt(5, RoundingMode.UNNECESSARY).toString());
    }

    @Test
    void stripTrailingZeros() {
        assertEquals("1.5", d("1.500").stripTrailingZeros().toString());
        assertEquals("1E+3", d("1000").stripTrailingZeros().toString());
        Dec z = d("0.000").stripTrailingZeros();
        assertEquals(0, z.scale());
        assertEquals(0, z.signum());
    }

    @Test
    void ofLongAndOfUnscaled() {
        assertEquals("-42", Dec.of(-42L).toString());
        assertEquals("1.23", Dec.of(BigInteger.valueOf(123), 2).toString());
        assertEquals("1.23E+7", Dec.of(BigInteger.valueOf(123), -5).toString());
    }

    @Test
    void scaleOverflowReported() {
        Dec big = Dec.of(BigInteger.ONE, Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> big.multiply(big));
    }

    // sqrt(6.25) = 2.5 and sqrt(12.25) = 3.5 are exact half-way cases at precision 1.
    @Test
    void sqrtExactHalfWayTies() {
        assertEquals("3", d("6.25").sqrt(1, RoundingMode.HALF_UP).toString());
        assertEquals("2", d("6.25").sqrt(1, RoundingMode.HALF_DOWN).toString());
        assertEquals("2", d("6.25").sqrt(1, RoundingMode.HALF_EVEN).toString());
        assertEquals("4", d("12.25").sqrt(1, RoundingMode.HALF_EVEN).toString());
        assertEquals("3", d("12.25").sqrt(1, RoundingMode.HALF_DOWN).toString());
        assertEquals("2.5", d("6.25").sqrt(2, RoundingMode.UNNECESSARY).toString());
        assertThrows(ArithmeticException.class, () -> d("6.25").sqrt(1, RoundingMode.UNNECESSARY));
    }

    @Test
    void equalValuesShareHashCode() {
        assertEquals(d("1.5"), d("15e-1"));
        assertEquals(d("1.5").hashCode(), d("15e-1").hashCode());
    }
}
