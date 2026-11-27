package ae.dec;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Randomised comparison against java.math.BigDecimal (oracle). Seeds are fixed. */
class DecDifferentialTest {

    private static final RoundingMode[] MODES = RoundingMode.values(); // all 8, UNNECESSARY included
    private static final int N = 4000;

    private static BigDecimal bd(Dec d) {
        return new BigDecimal(d.unscaled(), d.scale());
    }

    /** Random decimal: 1-25 digits (zero sometimes, trailing zeros often), scale in [-8, 12]. */
    private static String gen(Random r) {
        int kind = r.nextInt(10);
        if (kind == 0) {
            return "0" + (r.nextBoolean() ? "." + "0".repeat(r.nextInt(4)) : "");
        }
        int digits = 1 + r.nextInt(25);
        StringBuilder sb = new StringBuilder();
        if (r.nextBoolean()) {
            sb.append('-');
        }
        sb.append(1 + r.nextInt(9));
        for (int i = 1; i < digits; i++) {
            // lots of 0, 5 and 9 so halves, carries and trailing zeros occur
            int pick = r.nextInt(6);
            sb.append(pick == 0 ? '0' : pick == 1 ? '5' : pick == 2 ? '9' : (char) ('0' + r.nextInt(10)));
        }
        if (kind <= 2) {
            sb.append("0".repeat(r.nextInt(4)));
        }
        sb.append('E').append(r.nextInt(21) - 12);
        return sb.toString();
    }

    private static String describe(String a, String b, Object extra) {
        return "a=" + a + " b=" + b + " " + extra;
    }

    @Test
    void parseAndFormatMatchOracle() {
        Random r = new Random(1);
        for (int i = 0; i < N; i++) {
            String s = gen(r);
            Dec x = Dec.parse(s);
            BigDecimal o = new BigDecimal(s);
            assertEquals(o, bd(x), s);
            assertEquals(o.toString(), x.toString(), s);
            assertEquals(o.toPlainString(), x.toPlainString(), s);
            assertEquals(o.precision(), x.precision(), s);
            assertEquals(o.signum(), x.signum(), s);
            assertEquals(o.stripTrailingZeros(), bd(x.stripTrailingZeros()), s);
        }
    }

    @Test
    void addSubMulCompareMatchOracle() {
        Random r = new Random(2);
        for (int i = 0; i < N; i++) {
            String sa = gen(r);
            String sb = gen(r);
            Dec a = Dec.parse(sa);
            Dec b = Dec.parse(sb);
            BigDecimal oa = new BigDecimal(sa);
            BigDecimal ob = new BigDecimal(sb);
            String m = describe(sa, sb, "");
            assertEquals(oa.add(ob), bd(a.add(b)), m);
            assertEquals(oa.subtract(ob), bd(a.subtract(b)), m);
            assertEquals(oa.multiply(ob), bd(a.multiply(b)), m);
            assertEquals(Integer.signum(oa.compareTo(ob)), Integer.signum(a.compareTo(b)), m);
            assertEquals(oa.equals(ob), a.equals(b), m);
            assertEquals(oa.negate(), bd(a.negate()), m);
            assertEquals(oa.abs(), bd(a.abs()), m);
        }
    }

    @Test
    void setScaleMatchesOracleInEveryMode() {
        Random r = new Random(3);
        for (int i = 0; i < N; i++) {
            String sa = gen(r);
            Dec a = Dec.parse(sa);
            BigDecimal oa = new BigDecimal(sa);
            int ns = r.nextInt(25) - 10;
            RoundingMode mode = MODES[r.nextInt(MODES.length)];
            String m = describe(sa, "", "scale=" + ns + " mode=" + mode);
            BigDecimal expected = null;
            try {
                expected = oa.setScale(ns, mode);
            } catch (ArithmeticException e) {
                assertThrows(ArithmeticException.class, () -> a.setScale(ns, mode), m);
                continue;
            }
            assertEquals(expected, bd(a.setScale(ns, mode)), m);
        }
    }

    @Test
    void roundMatchesOracleInEveryMode() {
        Random r = new Random(4);
        for (int i = 0; i < N; i++) {
            String sa = gen(r);
            Dec a = Dec.parse(sa);
            BigDecimal oa = new BigDecimal(sa);
            int p = r.nextInt(12);
            RoundingMode mode = MODES[r.nextInt(MODES.length)];
            String m = describe(sa, "", "prec=" + p + " mode=" + mode);
            BigDecimal expected;
            try {
                expected = oa.round(new MathContext(p, mode));
            } catch (ArithmeticException e) {
                assertThrows(ArithmeticException.class, () -> a.round(p, mode), m);
                continue;
            }
            assertEquals(expected, bd(a.round(p, mode)), m);
        }
    }

    @Test
    void divideMatchesOracleInEveryMode() {
        Random r = new Random(5);
        int checked = 0;
        for (int i = 0; i < N; i++) {
            String sa = gen(r);
            String sb = gen(r);
            Dec a = Dec.parse(sa);
            Dec b = Dec.parse(sb);
            BigDecimal oa = new BigDecimal(sa);
            BigDecimal ob = new BigDecimal(sb);
            int p = 1 + r.nextInt(30);
            RoundingMode mode = MODES[r.nextInt(MODES.length)];
            String m = describe(sa, sb, "prec=" + p + " mode=" + mode);
            BigDecimal expected;
            try {
                expected = oa.divide(ob, new MathContext(p, mode));
            } catch (ArithmeticException e) {
                assertThrows(ArithmeticException.class, () -> a.divide(b, p, mode), m);
                continue;
            }
            assertEquals(expected, bd(a.divide(b, p, mode)), m);
            checked++;
        }
        assertTrue(checked > N / 2, "too few non-throwing divisions: " + checked);
    }

    @Test
    void divideExactMatchesOracle() {
        Random r = new Random(6);
        int terminating = 0;
        for (int i = 0; i < N; i++) {
            String sa = gen(r);
            String sb = r.nextInt(3) == 0 ? gen(r) : (1 << r.nextInt(6)) * (r.nextBoolean() ? 1 : 5) + "E" + (r.nextInt(5) - 2);
            Dec a = Dec.parse(sa);
            Dec b = Dec.parse(sb);
            BigDecimal oa = new BigDecimal(sa);
            BigDecimal ob = new BigDecimal(sb);
            String m = describe(sa, sb, "");
            BigDecimal expected;
            try {
                expected = oa.divide(ob);
            } catch (ArithmeticException e) {
                assertThrows(ArithmeticException.class, () -> a.divideExact(b), m);
                continue;
            }
            assertEquals(expected, bd(a.divideExact(b)), m);
            terminating++;
        }
        assertTrue(terminating > N / 4, "too few terminating quotients: " + terminating);
    }

    @Test
    void sqrtMatchesOracleNumerically() {
        Random r = new Random(7);
        int exact = 0;
        for (int i = 0; i < N; i++) {
            String sa = gen(r).replace("-", "");
            if (r.nextInt(4) == 0) { // perfect squares, with scale
                long k = 1 + r.nextInt(100000);
                sa = (k * k) + "E" + (-2 * r.nextInt(4));
            }
            Dec a = Dec.parse(sa);
            BigDecimal oa = new BigDecimal(sa);
            int p = 1 + r.nextInt(30);
            RoundingMode mode = MODES[r.nextInt(MODES.length)];
            String m = describe(sa, "", "prec=" + p + " mode=" + mode);
            BigDecimal expected;
            try {
                expected = oa.sqrt(new MathContext(p, mode));
            } catch (ArithmeticException e) {
                assertThrows(ArithmeticException.class, () -> a.sqrt(p, mode), m);
                continue;
            }
            Dec got = a.sqrt(p, mode);
            assertEquals(0, expected.compareTo(bd(got)), m + " expected=" + expected + " got=" + got);
            assertTrue(got.precision() <= p, m);
            if (oa.signum() != 0 && expected.multiply(expected).compareTo(oa) == 0) {
                exact++;
            }
        }
        assertTrue(exact > 100, "too few exact roots: " + exact);
    }

    @Test
    void powMatchesOracle() {
        Random r = new Random(8);
        for (int i = 0; i < 600; i++) {
            String sa = gen(r);
            int n = r.nextInt(8);
            assertEquals(new BigDecimal(sa).pow(n), bd(Dec.parse(sa).pow(n)), sa + "^" + n);
        }
    }
}
