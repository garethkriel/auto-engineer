package ae.sf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SoftDoubleTest {
    private static final long NAN = 0x7FF8000000000000L;
    private static final long MIN_SUB = 1L;
    private static final long MAX = 0x7FEFFFFFFFFFFFFFL;
    private static final long MIN_NORMAL = 0x0010000000000000L;

    private static long b(double d) {
        return Double.doubleToRawLongBits(d);
    }

    /** Compares bits, treating every NaN as equal to every other NaN. */
    private static void same(double expected, long actual, String msg) {
        if (Double.isNaN(expected)) {
            assertEquals(NAN, actual, msg + " (expected NaN)");
        } else {
            assertEquals(b(expected), actual,
                msg + " expected " + expected + " got " + Double.longBitsToDouble(actual));
        }
    }

    // ---- published literals ----

    @Test
    void pointOnePlusPointTwoIsPointThreeZeroFour() {
        assertEquals(0x3FD3333333333334L, SoftDouble.add(b(0.1), b(0.2)));
    }

    @Test
    void oneThirdSqrtTwoAndProducts() {
        assertEquals(0x3FD5555555555555L, SoftDouble.div(b(1.0), b(3.0)));
        assertEquals(0x3FF6A09E667F3BCDL, SoftDouble.sqrt(b(2.0)));
        assertEquals(0x3FD3333333333334L, SoftDouble.mul(b(0.1), b(3.0)));
    }

    @Test
    void tieToEvenAtTwoToThe53() {
        // 2^53 + 1 is a tie: rounds to even (2^53); 2^53 + 3 rounds to 2^53 + 4.
        assertEquals(b(9007199254740992.0), SoftDouble.add(b(9007199254740992.0), b(1.0)));
        assertEquals(b(9007199254740996.0), SoftDouble.add(b(9007199254740994.0), b(3.0)));
        assertEquals(0x4340000000000000L, SoftDouble.fromLong(9007199254740993L));
        assertEquals(0x4340000000000002L, SoftDouble.fromLong(9007199254740995L));
    }

    @Test
    void overflowAndUnderflowBoundaries() {
        assertEquals(SoftDouble.POS_INF, SoftDouble.add(MAX, b(Math.ulp(Double.MAX_VALUE))));
        // half an ulp above MAX is a tie that rounds to even -> infinity
        assertEquals(SoftDouble.POS_INF, SoftDouble.add(MAX, b(Math.ulp(Double.MAX_VALUE) / 2)));
        assertEquals(MAX, SoftDouble.add(MAX, b(Math.ulp(Double.MAX_VALUE) / 4)));
        assertEquals(0L, SoftDouble.mul(MIN_SUB, b(0.5)));              // tie to even -> 0
        assertEquals(2L, SoftDouble.mul(3L, b(0.5)));                    // 1.5 -> 2
        assertEquals(MIN_NORMAL, SoftDouble.sub(MIN_NORMAL + 1L, MIN_SUB));
        assertEquals(MIN_SUB, SoftDouble.sub(MIN_SUB + 1L, MIN_SUB));
        assertEquals(SoftDouble.NEG_INF, SoftDouble.mul(MAX, b(-4.0)));
    }

    // ---- special-value tables ----

    @Test
    void signedZeroAndInfinityRules() {
        long pz = 0L, nz = Long.MIN_VALUE, one = b(1.0);
        assertEquals(pz, SoftDouble.add(pz, nz));
        assertEquals(nz, SoftDouble.add(nz, nz));
        assertEquals(pz, SoftDouble.sub(one, one));
        assertEquals(pz, SoftDouble.add(one, b(-1.0)));
        assertEquals(pz, SoftDouble.sub(pz, pz));
        assertEquals(nz, SoftDouble.sub(nz, pz));
        assertEquals(nz, SoftDouble.mul(pz, b(-2.0)));
        assertEquals(nz, SoftDouble.div(pz, b(-2.0)));
        assertEquals(SoftDouble.NEG_INF, SoftDouble.div(one, nz));
        assertEquals(SoftDouble.POS_INF, SoftDouble.div(b(-1.0), nz));
        assertEquals(nz, SoftDouble.div(b(-1.0), SoftDouble.POS_INF));
        assertEquals(NAN, SoftDouble.sub(SoftDouble.POS_INF, SoftDouble.POS_INF));
        assertEquals(SoftDouble.POS_INF, SoftDouble.add(SoftDouble.POS_INF, one));
        assertEquals(NAN, SoftDouble.mul(SoftDouble.POS_INF, pz));
        assertEquals(NAN, SoftDouble.div(pz, pz));
        assertEquals(NAN, SoftDouble.div(SoftDouble.POS_INF, SoftDouble.NEG_INF));
        assertEquals(nz, SoftDouble.sqrt(nz));
        assertEquals(NAN, SoftDouble.sqrt(b(-4.0)));
        assertEquals(NAN, SoftDouble.sqrt(SoftDouble.NEG_INF));
        assertEquals(SoftDouble.POS_INF, SoftDouble.sqrt(SoftDouble.POS_INF));
        assertEquals(b(2.0), SoftDouble.sqrt(b(4.0)));
        assertEquals(NAN, SoftDouble.add(0x7FF0000000000001L, one));
        assertEquals(NAN, SoftDouble.fma(SoftDouble.POS_INF, pz, one));
        assertEquals(NAN, SoftDouble.fma(SoftDouble.POS_INF, one, SoftDouble.NEG_INF));
        assertEquals(pz, SoftDouble.fma(pz, one, nz));
        assertEquals(nz, SoftDouble.fma(nz, one, nz));
        assertEquals(b(5.0), SoftDouble.fma(pz, one, b(5.0)));
    }

    @Test
    void comparisonsFollowIeee() {
        long nan = NAN;
        assertFalse(SoftDouble.lessThan(nan, b(1.0)));
        assertFalse(SoftDouble.lessThan(b(1.0), nan));
        assertFalse(SoftDouble.equal(nan, nan));
        assertTrue(SoftDouble.equal(0L, Long.MIN_VALUE));
        assertFalse(SoftDouble.lessThan(Long.MIN_VALUE, 0L));
        assertTrue(SoftDouble.lessThan(b(-2.0), b(-1.0)));
        assertTrue(SoftDouble.lessThan(b(-1.0), 0L));
        assertTrue(SoftDouble.lessThan(SoftDouble.NEG_INF, b(-Double.MAX_VALUE)));
        assertTrue(SoftDouble.lessThan(b(Double.MAX_VALUE), SoftDouble.POS_INF));
        assertFalse(SoftDouble.lessThan(b(3.0), b(3.0)));
    }

    @Test
    void integerConversions() {
        assertEquals(0L, SoftDouble.fromLong(0));
        assertEquals(b(-1.0), SoftDouble.fromLong(-1));
        assertEquals(b((double) Long.MIN_VALUE), SoftDouble.fromLong(Long.MIN_VALUE));
        assertEquals(b((double) Long.MAX_VALUE), SoftDouble.fromLong(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, SoftDouble.toLong(b(1e30)));
        assertEquals(Long.MIN_VALUE, SoftDouble.toLong(b(-1e30)));
        assertEquals(Long.MIN_VALUE, SoftDouble.toLong(b(-9.223372036854775808e18)));
        assertEquals(0L, SoftDouble.toLong(NAN));
        assertEquals(-3L, SoftDouble.toLong(b(-3.99)));
        assertEquals(0L, SoftDouble.toLong(b(0.999)));
        assertEquals(Long.MAX_VALUE, SoftDouble.toLong(SoftDouble.POS_INF));
        assertEquals(1L << 62, SoftDouble.toLong(b(0x1p62)));
    }

    // ---- randomized differential tests against the JDK ----

    private static List<Long> interesting() {
        List<Long> v = new ArrayList<>();
        double[] ds = {0.0, -0.0, 1.0, -1.0, 0.5, 3.0, 0.1, 1e-310, -1e-310, Double.MIN_VALUE,
            Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NaN, 1e300, 1e-300, 4.5e15, 0x1p53, 0x1p-1074 * 3};
        for (double d : ds) {
            v.add(b(d));
        }
        return v;
    }

    private static long randomBits(Random r) {
        switch (r.nextInt(5)) {
            case 0:
                return r.nextLong();
            case 1: { // subnormal
                long f = r.nextLong() & ((1L << 52) - 1);
                return (r.nextBoolean() ? Long.MIN_VALUE : 0L) | f;
            }
            case 2: { // exponent near the middle, dense cancellation cases
                long e = 1023 + r.nextInt(8) - 4;
                return (r.nextBoolean() ? Long.MIN_VALUE : 0L) | (e << 52) | (r.nextLong() & ((1L << 52) - 1));
            }
            case 3: { // extremes of the exponent range
                long e = r.nextBoolean() ? 1 + r.nextInt(6) : 2040 + r.nextInt(7);
                return (r.nextBoolean() ? Long.MIN_VALUE : 0L) | (e << 52) | (r.nextLong() & ((1L << 52) - 1));
            }
            default: { // sparse mantissa: ties and exact cases
                long e = 1 + r.nextInt(2045);
                long f = r.nextBoolean() ? (1L << r.nextInt(52)) : 0L;
                return (r.nextBoolean() ? Long.MIN_VALUE : 0L) | (e << 52) | f;
            }
        }
    }

    @Test
    void binaryOperationsMatchJdkOnInterestingPairs() {
        List<Long> v = interesting();
        for (long x : v) {
            for (long y : v) {
                double a = Double.longBitsToDouble(x);
                double c = Double.longBitsToDouble(y);
                String m = a + " , " + c;
                same(a + c, SoftDouble.add(x, y), "add " + m);
                same(a - c, SoftDouble.sub(x, y), "sub " + m);
                same(a * c, SoftDouble.mul(x, y), "mul " + m);
                same(a / c, SoftDouble.div(x, y), "div " + m);
                assertEquals(a < c, SoftDouble.lessThan(x, y), "lt " + m);
                assertEquals(a == c, SoftDouble.equal(x, y), "eq " + m);
                for (long z : v) {
                    double d = Double.longBitsToDouble(z);
                    same(Math.fma(a, c, d), SoftDouble.fma(x, y, z), "fma " + m + " , " + d);
                }
            }
            double a = Double.longBitsToDouble(x);
            same(Math.sqrt(a), SoftDouble.sqrt(x), "sqrt " + a);
        }
    }

    @Test
    void binaryOperationsMatchJdkOnRandomPairs() {
        Random r = new Random(20261127L);
        for (int i = 0; i < 40000; i++) {
            long x = randomBits(r);
            long y = randomBits(r);
            double a = Double.longBitsToDouble(x);
            double c = Double.longBitsToDouble(y);
            String m = Long.toHexString(x) + " , " + Long.toHexString(y);
            same(a + c, SoftDouble.add(x, y), "add " + m);
            same(a - c, SoftDouble.sub(x, y), "sub " + m);
            same(a * c, SoftDouble.mul(x, y), "mul " + m);
            same(a / c, SoftDouble.div(x, y), "div " + m);
            assertEquals(a < c, SoftDouble.lessThan(x, y), "lt " + m);
        }
    }

    @Test
    void sqrtAndFmaMatchJdkOnRandomInputs() {
        Random r = new Random(7L);
        for (int i = 0; i < 20000; i++) {
            long x = randomBits(r);
            long y = randomBits(r);
            long z = randomBits(r);
            double a = Double.longBitsToDouble(x);
            same(Math.sqrt(a), SoftDouble.sqrt(x), "sqrt " + Long.toHexString(x));
            same(Math.fma(a, Double.longBitsToDouble(y), Double.longBitsToDouble(z)),
                SoftDouble.fma(x, y, z), "fma " + Long.toHexString(x) + " " + Long.toHexString(y)
                    + " " + Long.toHexString(z));
        }
    }

    @Test
    void fmaDiffersFromSeparateMulAddWhenTheProductNeedsRounding() {
        // (1 + 2^-52) * (1 - 2^-52) - 1 = -2^-104 exactly with fma, 0 with mul then add.
        long a = b(1.0 + 0x1p-52);
        long c = b(1.0 - 0x1p-53 * 2);
        long minusOne = b(-1.0);
        assertEquals(b(-0x1p-104), SoftDouble.fma(a, c, minusOne));
        assertEquals(0L, SoftDouble.add(SoftDouble.mul(a, c), minusOne));
    }

    @Test
    void correctlyRoundedResultsAreStableForConversionsOnRandomLongs() {
        Random r = new Random(99L);
        for (int i = 0; i < 20000; i++) {
            long v = r.nextLong() >> r.nextInt(64);
            assertEquals(b((double) v), SoftDouble.fromLong(v), "fromLong " + v);
            double d = Double.longBitsToDouble(randomBits(r));
            assertEquals((long) d, SoftDouble.toLong(b(d)), "toLong " + d);
        }
    }

    @Test
    void sqrtOfPerfectSquaresIsExactAndDivisionByPowersOfTwoIsExact() {
        for (long k = 1; k < 3000; k++) {
            assertEquals(b((double) k), SoftDouble.sqrt(b((double) (k * k))), "sqrt " + k * k);
            assertEquals(b(k / 1024.0), SoftDouble.div(b((double) k), b(1024.0)));
        }
    }
}
