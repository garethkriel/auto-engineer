package ae.sf;

import java.math.BigInteger;

/**
 * IEEE-754 binary64 arithmetic on raw {@code long} bit patterns, written without using any
 * floating-point operation. Every finite operation computes the exact mathematical result as
 * {@code mag * 2^exp} (division and square root append a sticky bit to a quotient that is long
 * enough to decide the rounding) and rounds exactly once, to nearest with ties to even.
 *
 * <p>NaN results are always the canonical quiet NaN {@link #NAN}.
 */
public final class SoftDouble {
    /** Sign bit mask. */
    public static final long SIGN = Long.MIN_VALUE;
    /** +Infinity. */
    public static final long POS_INF = 0x7FF0000000000000L;
    /** -Infinity. */
    public static final long NEG_INF = 0xFFF0000000000000L;
    /** Canonical quiet NaN. */
    public static final long NAN = 0x7FF8000000000000L;

    private static final int FRAC_BITS = 52;
    private static final long FRAC_MASK = (1L << FRAC_BITS) - 1;
    private static final int MIN_EXP = -1022;
    private static final int SUBNORMAL_EXP = -1074;
    private static final int GUARD_BITS = 55;

    private SoftDouble() {
    }

    // ---- classification ----

    /** True for any NaN payload. */
    public static boolean isNaN(long bits) {
        return (bits & ~SIGN) > POS_INF;
    }

    /** True for +Infinity and -Infinity. */
    public static boolean isInfinite(long bits) {
        return (bits & ~SIGN) == POS_INF;
    }

    /** True for +0 and -0. */
    public static boolean isZero(long bits) {
        return (bits & ~SIGN) == 0L;
    }

    /** Flips the sign bit (also of NaN and zero). */
    public static long neg(long bits) {
        return bits ^ SIGN;
    }

    /** Clears the sign bit. */
    public static long abs(long bits) {
        return bits & ~SIGN;
    }

    private static boolean negative(long bits) {
        return bits < 0;
    }

    // ---- exact unpacking and the single rounding step ----

    /** A finite nonzero value {@code (neg ? -1 : 1) * mag * 2^exp}. */
    record Unpacked(boolean neg, BigInteger mag, int exp) {
    }

    static Unpacked unpack(long bits) {
        int biased = (int) ((bits >>> FRAC_BITS) & 0x7FF);
        long frac = bits & FRAC_MASK;
        if (biased == 0) {
            return new Unpacked(negative(bits), BigInteger.valueOf(frac), SUBNORMAL_EXP);
        }
        return new Unpacked(negative(bits), BigInteger.valueOf(frac | (1L << FRAC_BITS)), biased - 1075);
    }

    /** Rounds {@code (neg ? -1 : 1) * m * 2^e2} (m &gt; 0) to the nearest binary64, ties to even. */
    static long round(boolean neg, BigInteger m, int e2) {
        long signBit = neg ? SIGN : 0L;
        int top = e2 + m.bitLength() - 1;
        if (top > 1024) {
            return signBit | POS_INF;
        }
        int exponent = Math.max(top, MIN_EXP);
        int shift = exponent - FRAC_BITS - e2;
        BigInteger q;
        if (shift <= 0) {
            q = m.shiftLeft(-shift);
        } else {
            q = m.shiftRight(shift);
            boolean half = m.testBit(shift - 1);
            boolean sticky = m.getLowestSetBit() < shift - 1;
            if (half && (sticky || q.testBit(0))) {
                q = q.add(BigInteger.ONE);
            }
        }
        // q has 53 bits (normal; its top bit is the hidden bit) or fewer (subnormal). A carry
        // out of the significand lands in the exponent field, which is exactly the right result.
        long bits = ((long) (exponent + 1022) << FRAC_BITS) + q.longValueExact();
        if (bits >= POS_INF) {
            return signBit | POS_INF;
        }
        return signBit | bits;
    }

    /** Rounds {@code num / den} (both positive) with a sticky bit appended to a long quotient. */
    static long roundRatio(boolean neg, BigInteger num, BigInteger den) {
        int k = Math.max(0, GUARD_BITS + den.bitLength() - num.bitLength());
        BigInteger[] qr = num.shiftLeft(k).divideAndRemainder(den);
        BigInteger q = qr[0];
        int e = -k;
        if (qr[1].signum() != 0) {
            q = q.shiftLeft(1).or(BigInteger.ONE);
            e--;
        }
        return round(neg, q, e);
    }

    /** Exact signed sum of two finite values given as magnitude/exponent, rounded once. */
    private static long sumRound(boolean n1, BigInteger m1, int e1, boolean n2, BigInteger m2, int e2) {
        int e = Math.min(e1, e2);
        BigInteger a = m1.shiftLeft(e1 - e);
        BigInteger b = m2.shiftLeft(e2 - e);
        BigInteger sum = (n1 ? a.negate() : a).add(n2 ? b.negate() : b);
        if (sum.signum() == 0) {
            return 0L;
        }
        return round(sum.signum() < 0, sum.abs(), e);
    }

    // ---- arithmetic ----

    /** Correctly rounded {@code a + b}. */
    public static long add(long a, long b) {
        if (isNaN(a) || isNaN(b)) {
            return NAN;
        }
        if (isInfinite(a) || isInfinite(b)) {
            if (isInfinite(a) && isInfinite(b) && negative(a) != negative(b)) {
                return NAN;
            }
            return isInfinite(a) ? a : b;
        }
        if (isZero(a) && isZero(b)) {
            return a & b & SIGN;
        }
        if (isZero(a)) {
            return b;
        }
        if (isZero(b)) {
            return a;
        }
        Unpacked x = unpack(a);
        Unpacked y = unpack(b);
        return sumRound(x.neg(), x.mag(), x.exp(), y.neg(), y.mag(), y.exp());
    }

    /** Correctly rounded {@code a - b}. */
    public static long sub(long a, long b) {
        return add(a, isNaN(b) ? b : neg(b));
    }

    /** Correctly rounded {@code a * b}. */
    public static long mul(long a, long b) {
        if (isNaN(a) || isNaN(b)) {
            return NAN;
        }
        long sign = (a ^ b) & SIGN;
        if (isInfinite(a) || isInfinite(b)) {
            return isZero(a) || isZero(b) ? NAN : sign | POS_INF;
        }
        if (isZero(a) || isZero(b)) {
            return sign;
        }
        Unpacked x = unpack(a);
        Unpacked y = unpack(b);
        return round(sign != 0, x.mag().multiply(y.mag()), x.exp() + y.exp());
    }

    /** Correctly rounded {@code a / b}. */
    public static long div(long a, long b) {
        if (isNaN(a) || isNaN(b)) {
            return NAN;
        }
        long sign = (a ^ b) & SIGN;
        if (isInfinite(a)) {
            return isInfinite(b) ? NAN : sign | POS_INF;
        }
        if (isInfinite(b)) {
            return sign;
        }
        if (isZero(b)) {
            return isZero(a) ? NAN : sign | POS_INF;
        }
        if (isZero(a)) {
            return sign;
        }
        Unpacked x = unpack(a);
        Unpacked y = unpack(b);
        int k = Math.max(0, GUARD_BITS + y.mag().bitLength() - x.mag().bitLength());
        BigInteger[] qr = x.mag().shiftLeft(k).divideAndRemainder(y.mag());
        BigInteger q = qr[0];
        int e = x.exp() - y.exp() - k;
        if (qr[1].signum() != 0) {
            q = q.shiftLeft(1).or(BigInteger.ONE);
            e--;
        }
        return round(sign != 0, q, e);
    }

    /** Correctly rounded square root; negative operands (except -0) give NaN. */
    public static long sqrt(long a) {
        if (isNaN(a)) {
            return NAN;
        }
        if (isZero(a)) {
            return a;
        }
        if (negative(a)) {
            return NAN;
        }
        if (isInfinite(a)) {
            return POS_INF;
        }
        Unpacked x = unpack(a);
        // Scale so the root has at least GUARD_BITS bits and the remaining exponent is even.
        int shift = Math.max(0, 2 * GUARD_BITS - x.mag().bitLength());
        if (((x.exp() - shift) & 1) != 0) {
            shift++;
        }
        BigInteger scaled = x.mag().shiftLeft(shift);
        BigInteger root = scaled.sqrt();
        int e = (x.exp() - shift) / 2;
        if (!root.multiply(root).equals(scaled)) {
            root = root.shiftLeft(1).or(BigInteger.ONE);
            e--;
        }
        return round(false, root, e);
    }

    /** {@code a * b + c} with a single rounding. */
    public static long fma(long a, long b, long c) {
        if (isNaN(a) || isNaN(b) || isNaN(c)) {
            return NAN;
        }
        long prodSign = (a ^ b) & SIGN;
        if (isInfinite(a) || isInfinite(b)) {
            if (isZero(a) || isZero(b)) {
                return NAN;
            }
            if (isInfinite(c) && (c & SIGN) != prodSign) {
                return NAN;
            }
            return prodSign | POS_INF;
        }
        if (isInfinite(c)) {
            return c;
        }
        if (isZero(a) || isZero(b)) {
            return isZero(c) ? prodSign & c : c;
        }
        Unpacked x = unpack(a);
        Unpacked y = unpack(b);
        BigInteger pm = x.mag().multiply(y.mag());
        int pe = x.exp() + y.exp();
        if (isZero(c)) {
            return round(prodSign != 0, pm, pe);
        }
        Unpacked z = unpack(c);
        return sumRound(prodSign != 0, pm, pe, z.neg(), z.mag(), z.exp());
    }

    // ---- comparison ----

    private static long key(long bits) {
        return bits >= 0 ? bits : -(bits & Long.MAX_VALUE);
    }

    /** IEEE {@code a < b}; false when either is NaN, and -0 is not less than +0. */
    public static boolean lessThan(long a, long b) {
        return !isNaN(a) && !isNaN(b) && key(a) < key(b);
    }

    /** IEEE {@code a == b}; false when either is NaN, and -0 equals +0. */
    public static boolean equal(long a, long b) {
        return !isNaN(a) && !isNaN(b) && key(a) == key(b);
    }

    // ---- integer conversion ----

    /** The nearest binary64 to {@code v}, ties to even. */
    public static long fromLong(long v) {
        if (v == 0) {
            return 0L;
        }
        return round(v < 0, BigInteger.valueOf(v).abs(), 0);
    }

    /** Truncates toward zero like a Java {@code (long)} cast: saturating, NaN becomes 0. */
    public static long toLong(long bits) {
        if (isNaN(bits)) {
            return 0L;
        }
        if (isInfinite(bits)) {
            return negative(bits) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        if (isZero(bits)) {
            return 0L;
        }
        Unpacked x = unpack(bits);
        BigInteger v = x.exp() >= 0 ? x.mag().shiftLeft(x.exp()) : x.mag().shiftRight(-x.exp());
        if (x.neg()) {
            v = v.negate();
        }
        if (v.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return Long.MAX_VALUE;
        }
        if (v.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
            return Long.MIN_VALUE;
        }
        return v.longValue();
    }
}
