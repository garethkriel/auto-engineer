package ae.sf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DecimalTest {
    private static long b(double d) {
        return Double.doubleToRawLongBits(d);
    }

    // ---- parse: literals ----

    @Test
    void parsesPublishedBoundaryLiterals() {
        assertEquals(0x3FB999999999999AL, Decimal.parse("0.1"));
        assertEquals(1L, Decimal.parse("4.9e-324"));
        // Exactly half the smallest subnormal is 2.4703282292062327208...e-324: below -> 0, above -> 1.
        assertEquals(0L, Decimal.parse("2.4703282292062327e-324"));
        assertEquals(1L, Decimal.parse("2.4703282292062328e-324"));
        assertEquals(0x000FFFFFFFFFFFFFL, Decimal.parse("2.2250738585072011e-308"));
        assertEquals(0x0010000000000000L, Decimal.parse("2.2250738585072012e-308"));
        assertEquals(0x7FEFFFFFFFFFFFFFL, Decimal.parse("1.7976931348623157e308"));
        // Halfway to 2^1024 is 1.797693134862315807...e308.
        assertEquals(0x7FEFFFFFFFFFFFFFL, Decimal.parse("1.7976931348623158e308"));
        assertEquals(SoftDouble.POS_INF, Decimal.parse("1.7976931348623159e308"));
        assertEquals(0x4340000000000000L, Decimal.parse("9007199254740993"));
        assertEquals(0x4340000000000001L, Decimal.parse("9007199254740993.0000001"));
        assertEquals(0x4340000000000002L, Decimal.parse("9007199254740995"));
    }

    @Test
    void parsesSignsZerosAndSpecials() {
        assertEquals(Long.MIN_VALUE, Decimal.parse("-0"));
        assertEquals(0L, Decimal.parse("+0.000e5"));
        assertEquals(0L, Decimal.parse("0e999999999999"));
        assertEquals(b(100.0), Decimal.parse("1e2"));
        assertEquals(b(-0.5), Decimal.parse("-.5"));
        assertEquals(b(5.0), Decimal.parse("5."));
        assertEquals(b(1.0), Decimal.parse("0000001.000000"));
        assertEquals(SoftDouble.POS_INF, Decimal.parse("1e999999999999999999999"));
        assertEquals(0L, Decimal.parse("1e-999999999999999999999"));
        assertEquals(SoftDouble.NEG_INF, Decimal.parse("-Infinity"));
        assertEquals(SoftDouble.POS_INF, Decimal.parse("+Infinity"));
        assertEquals(0x7FF8000000000000L, Decimal.parse("NaN"));
        assertEquals(b(123.0), Decimal.parse("0.00000000000000000000000000123e29"));
    }

    @Test
    void rejectsEverythingOutsideTheGrammar() {
        String[] bad = {"", " 1", "1 ", "+", "-", ".", "e5", "1e", "1e+", "0x1p3", "1d", "1f", "1_0",
            "1,5", "--1", "1.2.3", "infinity", "nan", "Inf", "1e5.0", "١٢"};
        for (String s : bad) {
            assertThrows(NumberFormatException.class, () -> Decimal.parse(s), "should reject '" + s + "'");
        }
        assertThrows(NullPointerException.class, () -> Decimal.parse(null));
    }

    @Test
    void controlInputsDifferingByOneCharacterAreAccepted() {
        assertEquals(b(1.0), Decimal.parse("1"));
        assertEquals(b(15.0), Decimal.parse("1.5e1"));
    }

    // ---- parse: differential ----

    @Test
    void parseMatchesJdkOnRandomShortestStrings() {
        Random r = new Random(1L);
        for (int i = 0; i < 20000; i++) {
            double d = Double.longBitsToDouble(r.nextLong());
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                continue;
            }
            String s = Double.toString(d);
            assertEquals(b(Double.parseDouble(s)), Decimal.parse(s), s);
            assertEquals(b(d), Decimal.parse(s), "round trip " + s);
        }
    }

    @Test
    void parseMatchesJdkOnLongDigitStringsNearTies() {
        Random r = new Random(2L);
        for (int i = 0; i < 6000; i++) {
            double d = Double.longBitsToDouble(r.nextLong() & Long.MAX_VALUE);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                continue;
            }
            BigDecimal exact = new BigDecimal(d);
            BigDecimal ulp = new BigDecimal(Math.ulp(d));
            // The exact midpoint to the next double, then perturbed by tiny amounts either side.
            BigDecimal mid = exact.add(ulp.divide(BigDecimal.valueOf(2)));
            for (int k = -1; k <= 1; k++) {
                BigDecimal v = mid.add(BigDecimal.valueOf(k).multiply(ulp.movePointLeft(40)));
                String s = v.toString();
                assertEquals(b(Double.parseDouble(s)), Decimal.parse(s), s);
            }
            assertEquals(b(d), Decimal.parse(exact.toString()), "exact " + d);
        }
    }

    @Test
    void parseMatchesJdkOnRandomShapes() {
        Random r = new Random(3L);
        for (int i = 0; i < 20000; i++) {
            StringBuilder sb = new StringBuilder();
            if (r.nextBoolean()) {
                sb.append('-');
            }
            int n = 1 + r.nextInt(25);
            for (int k = 0; k < n; k++) {
                sb.append((char) ('0' + r.nextInt(10)));
            }
            if (r.nextBoolean()) {
                sb.append('.');
                int f = r.nextInt(25);
                for (int k = 0; k < f; k++) {
                    sb.append((char) ('0' + r.nextInt(10)));
                }
            }
            if (r.nextInt(3) > 0) {
                sb.append(r.nextBoolean() ? 'e' : 'E').append(r.nextInt(700) - 350);
            }
            String s = sb.toString();
            assertEquals(b(Double.parseDouble(s)), Decimal.parse(s), s);
        }
    }

    // ---- format ----

    @Test
    void formatsLiteralsLikeTheJdk() {
        assertEquals("0.0", Decimal.toString(0L));
        assertEquals("-0.0", Decimal.toString(Long.MIN_VALUE));
        assertEquals("NaN", Decimal.toString(0x7FF8000000000000L));
        assertEquals("Infinity", Decimal.toString(SoftDouble.POS_INF));
        assertEquals("-Infinity", Decimal.toString(SoftDouble.NEG_INF));
        assertEquals("1.0", Decimal.toString(b(1.0)));
        assertEquals("0.30000000000000004", Decimal.toString(0x3FD3333333333334L));
        assertEquals("4.9E-324", Decimal.toString(1L));
        assertEquals("1.7976931348623157E308", Decimal.toString(0x7FEFFFFFFFFFFFFFL));
        assertEquals("1.0E7", Decimal.toString(b(1e7)));
        assertEquals("9999999.0", Decimal.toString(b(9999999.0)));
        assertEquals("0.001", Decimal.toString(b(0.001)));
        assertEquals("9.999E-4", Decimal.toString(b(0.0009999)));
        assertEquals("1.0E23", Decimal.toString(b(1e23)));
        assertEquals("2.2250738585072014E-308", Decimal.toString(0x0010000000000000L));
        assertEquals("123456.789", Decimal.toString(b(123456.789)));
    }

    @Test
    void shortestIsTrulyShortestWithoutTheTwoDigitRule() {
        assertEquals(new Decimal.Digits("5", -324), Decimal.shortest(1L, 1));
        assertEquals(new Decimal.Digits("49", -324), Decimal.shortest(1L, 2));
        assertEquals(new Decimal.Digits("1", 23), Decimal.shortest(b(1e23), 1));
        assertEquals(new Decimal.Digits("9", -1), Decimal.shortest(b(0.9), 1));
        assertEquals(new Decimal.Digits("1", 0), Decimal.shortest(b(1.0), 1));
        assertEquals(new Decimal.Digits("123", 2), Decimal.shortest(b(123.0), 1));
    }

    @Test
    void formatMatchesJdkOnRandomBits() {
        Random r = new Random(4L);
        for (int i = 0; i < 40000; i++) {
            long bits = r.nextLong();
            if (i % 4 == 1) {
                bits &= 0x800FFFFFFFFFFFFFL; // subnormals
            } else if (i % 4 == 2) {
                bits &= 0xFFF0000000000000L | (1L << r.nextInt(52)); // powers of two and neighbours
            }
            double d = Double.longBitsToDouble(bits);
            assertEquals(Double.toString(d), Decimal.toString(bits), Long.toHexString(bits));
        }
    }

    @Test
    void formatMatchesJdkOnBinadeBoundariesAndNeighbours() {
        for (int e = 0; e < 2047; e++) {
            for (long delta = -2; delta <= 2; delta++) {
                long bits = ((long) e << 52) + delta;
                if (bits < 0) {
                    continue;
                }
                assertEquals(Double.toString(Double.longBitsToDouble(bits)), Decimal.toString(bits),
                    Long.toHexString(bits));
                assertEquals(Double.toString(Double.longBitsToDouble(bits | Long.MIN_VALUE)),
                    Decimal.toString(bits | Long.MIN_VALUE), Long.toHexString(bits | Long.MIN_VALUE));
            }
        }
    }

    @Test
    void shortestDigitCountMatchesBruteForceBigDecimalOracle() {
        Random r = new Random(5L);
        for (int i = 0; i < 4000; i++) {
            long bits = r.nextLong() & Long.MAX_VALUE;
            double d = Double.longBitsToDouble(bits);
            if (Double.isNaN(d) || Double.isInfinite(d) || d == 0.0) {
                continue;
            }
            BigDecimal exact = new BigDecimal(d);
            int best = -1;
            BigDecimal rounded = null;
            for (int n = 1; n <= 17; n++) {
                BigDecimal c = exact.round(new MathContext(n, RoundingMode.HALF_EVEN));
                if (Double.parseDouble(c.toString()) == d) {
                    best = n;
                    rounded = c;
                    break;
                }
            }
            Decimal.Digits got = Decimal.shortest(bits, 1);
            // Oracle: HALF_EVEN rounding of the exact value to n digits is the nearest n-digit
            // decimal; if any n-digit decimal round-trips the nearest one does (the interval
            // contains the value), so its length is the minimum.
            assertEquals(best, got.digits().length(), "digit count for " + d);
            BigDecimal gotValue = new BigDecimal(got.digits()).movePointLeft(got.digits().length() - 1)
                .movePointRight(got.exp10());
            assertEquals(0, rounded.compareTo(gotValue), "digits for " + d + " got " + got);
        }
    }

    @Test
    void parseOfFormatIsIdentityForFiniteBits() {
        Random r = new Random(6L);
        for (int i = 0; i < 20000; i++) {
            long bits = r.nextLong();
            if (SoftDouble.isNaN(bits) || SoftDouble.isInfinite(bits)) {
                continue;
            }
            assertEquals(bits, Decimal.parse(Decimal.toString(bits)), Long.toHexString(bits));
        }
    }

    @Test
    void shortestRejectsNonFiniteAndZero() {
        assertThrows(IllegalArgumentException.class, () -> Decimal.shortest(0L, 1));
        assertThrows(IllegalArgumentException.class, () -> Decimal.shortest(SoftDouble.POS_INF, 1));
        assertThrows(IllegalArgumentException.class, () -> Decimal.shortest(b(1.0), 0));
    }
}
