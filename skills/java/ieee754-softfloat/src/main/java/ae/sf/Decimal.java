package ae.sf;

import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exact decimal conversion for binary64 bit patterns: correctly rounded parsing of arbitrary
 * decimal strings and shortest round-trip formatting, both built on {@link BigInteger}.
 */
public final class Decimal {
    private static final Pattern NUMBER = Pattern.compile(
        "([+-])?(?:([0-9]+)(?:\\.([0-9]*))?|\\.([0-9]+))(?:[eE]([+-]?[0-9]+))?");
    private static final long EXP_CLAMP = 1_000_000_000L;
    private static final int MAX_DIGITS = 17;

    private Decimal() {
    }

    /**
     * Shortest digits: value is {@code d1.d2d3... * 10^exp10}, with no trailing zeros in
     * {@code digits}.
     */
    public record Digits(String digits, int exp10) {
    }

    // ---- parsing ----

    /**
     * Parses a decimal literal to the nearest binary64 (ties to even).
     *
     * @throws NumberFormatException if {@code s} is outside the grammar documented in TASK.md
     */
    public static long parse(String s) {
        Objects.requireNonNull(s, "s");
        switch (s) {
            case "NaN", "+NaN", "-NaN":
                return SoftDouble.NAN;
            case "Infinity", "+Infinity":
                return SoftDouble.POS_INF;
            case "-Infinity":
                return SoftDouble.NEG_INF;
            default:
                break;
        }
        Matcher m = NUMBER.matcher(s);
        if (!m.matches()) {
            throw new NumberFormatException("not a decimal number: '" + s + "'");
        }
        boolean neg = "-".equals(m.group(1));
        String intPart = m.group(2) != null ? m.group(2) : "";
        String fracPart = m.group(3) != null ? m.group(3) : (m.group(4) != null ? m.group(4) : "");
        long exp10 = m.group(5) == null ? 0 : clampedExponent(m.group(5));
        exp10 -= fracPart.length();
        String digits = stripLeadingZeros(intPart + fracPart);
        long signBit = neg ? SoftDouble.SIGN : 0L;
        if (digits.isEmpty()) {
            return signBit;
        }
        int trailing = 0;
        while (digits.charAt(digits.length() - 1 - trailing) == '0') {
            trailing++;
        }
        digits = digits.substring(0, digits.length() - trailing);
        exp10 += trailing;
        long magnitude10 = digits.length() + exp10; // value lies in [10^(mag-1), 10^mag)
        if (magnitude10 > 310) {
            return signBit | SoftDouble.POS_INF;
        }
        if (magnitude10 < -324) {
            return signBit;
        }
        BigInteger d = new BigInteger(digits);
        if (exp10 >= 0) {
            return SoftDouble.round(neg, d.multiply(BigInteger.TEN.pow((int) exp10)), 0);
        }
        return SoftDouble.roundRatio(neg, d, BigInteger.TEN.pow((int) -exp10));
    }

    private static long clampedExponent(String text) {
        boolean negative = text.startsWith("-");
        String digits = stripLeadingZeros(text.startsWith("+") || negative ? text.substring(1) : text);
        long v = digits.length() > 10 ? EXP_CLAMP : Math.min(EXP_CLAMP, digits.isEmpty() ? 0 : Long.parseLong(digits));
        return negative ? -v : v;
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    // ---- formatting ----

    /**
     * The shortest decimal (at least {@code minDigits} digits) that rounds back to {@code bits},
     * choosing the one closest to the exact value, ties to the even last digit.
     *
     * @throws IllegalArgumentException for zero, infinities, NaN or {@code minDigits < 1}
     */
    public static Digits shortest(long bits, int minDigits) {
        if (minDigits < 1) {
            throw new IllegalArgumentException("minDigits must be >= 1");
        }
        if (SoftDouble.isNaN(bits) || SoftDouble.isInfinite(bits) || SoftDouble.isZero(bits)) {
            throw new IllegalArgumentException("finite nonzero value required");
        }
        SoftDouble.Unpacked u = SoftDouble.unpack(bits & Long.MAX_VALUE);
        BigInteger m = u.mag();
        // Everything below is an integer multiple of 2^t: v = 4m, neighbours at +-1 or +-2.
        int t = u.exp() - 2;
        BigInteger value = m.shiftLeft(2);
        boolean powerOfTwoEdge = m.equals(BigInteger.ONE.shiftLeft(52)) && ((bits >>> 52) & 0x7FF) > 1;
        BigInteger high = value.add(BigInteger.TWO);
        BigInteger low = value.subtract(powerOfTwoEdge ? BigInteger.ONE : BigInteger.TWO);
        boolean inclusive = !m.testBit(0);

        int k = decimalExponent(value, t);
        for (int n = minDigits; n <= MAX_DIGITS + 2; n++) {
            int p = k - n + 1;
            // v / 10^p = num / den
            BigInteger num = scaled(value, t, p, true);
            BigInteger den = scaled(BigInteger.ONE, t, p, false);
            BigInteger[] qr = num.divideAndRemainder(den);
            BigInteger floor = qr[0];
            BigInteger ceil = floor.add(BigInteger.ONE);
            boolean exact = qr[1].signum() == 0;
            boolean floorOk = within(floor, p, low, high, t, inclusive);
            boolean ceilOk = !exact && within(ceil, p, low, high, t, inclusive);
            BigInteger chosen = null;
            if (floorOk && ceilOk) {
                int cmp = qr[1].compareTo(den.subtract(qr[1]));
                chosen = cmp < 0 ? floor : cmp > 0 ? ceil : (floor.testBit(0) ? ceil : floor);
            } else if (floorOk) {
                chosen = floor;
            } else if (ceilOk) {
                chosen = ceil;
            }
            if (chosen != null) {
                String digits = chosen.toString();
                int exp10 = k;
                if (digits.length() > n) { // carried to 10^n
                    exp10++;
                }
                int end = digits.length();
                while (end > 1 && digits.charAt(end - 1) == '0') {
                    end--;
                }
                return new Digits(digits.substring(0, end), exp10);
            }
        }
        throw new IllegalStateException("no round-tripping decimal found for " + Long.toHexString(bits));
    }

    /** {@code x * 2^max(t,0) * 10^max(-p,0)} when {@code numerator}, else the matching divisor part. */
    private static BigInteger scaled(BigInteger x, int t, int p, boolean numerator) {
        if (numerator) {
            return x.shiftLeft(Math.max(t, 0)).multiply(BigInteger.TEN.pow(Math.max(-p, 0)));
        }
        return x.shiftLeft(Math.max(-t, 0)).multiply(BigInteger.TEN.pow(Math.max(p, 0)));
    }

    /** Compares {@code c * 10^p} with {@code n * 2^t}. */
    private static int compare(BigInteger c, int p, BigInteger n, int t) {
        BigInteger lhs = c.multiply(BigInteger.TEN.pow(Math.max(p, 0))).shiftLeft(Math.max(-t, 0));
        BigInteger rhs = n.multiply(BigInteger.TEN.pow(Math.max(-p, 0))).shiftLeft(Math.max(t, 0));
        return lhs.compareTo(rhs);
    }

    private static boolean within(BigInteger c, int p, BigInteger low, BigInteger high, int t,
                                  boolean inclusive) {
        int lo = compare(c, p, low, t);
        int hi = compare(c, p, high, t);
        return inclusive ? lo >= 0 && hi <= 0 : lo > 0 && hi < 0;
    }

    /** The k with 10^k &lt;= value*2^t &lt; 10^(k+1). */
    private static int decimalExponent(BigInteger value, int t) {
        int k = (int) Math.floor((t + value.bitLength() - 1) * 0.30102999566398114);
        while (compare(BigInteger.ONE, k + 1, value, t) <= 0) {
            k++;
        }
        while (compare(BigInteger.ONE, k, value, t) > 0) {
            k--;
        }
        return k;
    }

    /** Same text as JDK 21 {@code Double.toString} for the value with bit pattern {@code bits}. */
    public static String toString(long bits) {
        if (SoftDouble.isNaN(bits)) {
            return "NaN";
        }
        String sign = bits < 0 ? "-" : "";
        if (SoftDouble.isInfinite(bits)) {
            return sign + "Infinity";
        }
        if (SoftDouble.isZero(bits)) {
            return sign + "0.0";
        }
        Digits d = shortest(bits, 2);
        String ds = d.digits();
        int x = d.exp10();
        StringBuilder sb = new StringBuilder(sign);
        if (x >= -3 && x < 7) {
            if (x >= 0) {
                if (ds.length() <= x + 1) {
                    sb.append(ds).append("0".repeat(x + 1 - ds.length())).append(".0");
                } else {
                    sb.append(ds, 0, x + 1).append('.').append(ds, x + 1, ds.length());
                }
            } else {
                sb.append("0.").append("0".repeat(-x - 1)).append(ds);
            }
        } else {
            sb.append(ds.charAt(0)).append('.');
            sb.append(ds.length() > 1 ? ds.substring(1) : "0");
            sb.append('E').append(x);
        }
        return sb.toString();
    }
}
