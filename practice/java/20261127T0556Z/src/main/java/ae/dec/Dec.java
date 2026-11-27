package ae.dec;

import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable arbitrary-precision decimal number {@code unscaled * 10^-scale}, built on {@link BigInteger}.
 * Semantics (scales, preferred scales, rounding, string forms) follow {@code java.math.BigDecimal}.
 */
public final class Dec implements Comparable<Dec> {

    private static final Pattern SYNTAX =
            Pattern.compile("([+-]?)(?:(\\d+)(?:\\.(\\d*))?|\\.(\\d+))(?:[eE]([+-]?\\d+))?");

    private final BigInteger unscaled;
    private final int scale;

    private Dec(BigInteger unscaled, int scale) {
        this.unscaled = unscaled;
        this.scale = scale;
    }

    // ---- construction ----------------------------------------------------------------------

    public static Dec of(long value) {
        return new Dec(BigInteger.valueOf(value), 0);
    }

    public static Dec of(BigInteger unscaled, int scale) {
        return new Dec(Objects.requireNonNull(unscaled), scale);
    }

    public static Dec parse(String text) {
        Objects.requireNonNull(text);
        Matcher m = SYNTAX.matcher(text);
        if (!m.matches()) {
            throw new NumberFormatException("not a decimal: \"" + text + "\"");
        }
        String intPart = m.group(2) != null ? m.group(2) : "";
        String frac = m.group(3) != null ? m.group(3) : (m.group(4) != null ? m.group(4) : "");
        long scale = frac.length();
        if (m.group(5) != null) {
            try {
                scale -= Integer.parseInt(m.group(5).startsWith("+") ? m.group(5).substring(1) : m.group(5));
            } catch (NumberFormatException e) {
                throw new NumberFormatException("exponent out of range: \"" + text + "\"");
            }
        }
        if (scale > Integer.MAX_VALUE || scale < Integer.MIN_VALUE) {
            throw new NumberFormatException("scale out of range: \"" + text + "\"");
        }
        BigInteger u = new BigInteger(intPart + frac);
        if (m.group(1).equals("-")) {
            u = u.negate();
        }
        return new Dec(u, (int) scale);
    }

    // ---- accessors -------------------------------------------------------------------------

    public BigInteger unscaled() {
        return unscaled;
    }

    public int scale() {
        return scale;
    }

    public int signum() {
        return unscaled.signum();
    }

    /** Number of decimal digits of the unscaled value (zero has precision 1). */
    public int precision() {
        return digits(unscaled);
    }

    // ---- arithmetic ------------------------------------------------------------------------

    public Dec add(Dec o) {
        int s = Math.max(scale, o.scale);
        return new Dec(raise(unscaled, s - scale).add(raise(o.unscaled, s - o.scale)), s);
    }

    public Dec subtract(Dec o) {
        return add(o.negate());
    }

    public Dec multiply(Dec o) {
        return new Dec(unscaled.multiply(o.unscaled), Math.addExact(scale, o.scale));
    }

    public Dec negate() {
        return new Dec(unscaled.negate(), scale);
    }

    public Dec abs() {
        return signum() < 0 ? negate() : this;
    }

    public Dec pow(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("negative exponent");
        }
        return new Dec(unscaled.pow(n), Math.multiplyExact(scale, n));
    }

    // ---- rounding --------------------------------------------------------------------------

    public Dec setScale(int newScale, RoundingMode mode) {
        Objects.requireNonNull(mode);
        if (newScale == scale) {
            return this;
        }
        if (newScale > scale) {
            return new Dec(raise(unscaled, Math.subtractExact(newScale, scale)), newScale);
        }
        int drop = Math.subtractExact(scale, newScale);
        return new Dec(divRound(unscaled, pow10(drop), mode), newScale);
    }

    public Dec round(int precision, RoundingMode mode) {
        Objects.requireNonNull(mode);
        if (precision < 0) {
            throw new IllegalArgumentException("negative precision");
        }
        Dec cur = this;
        while (precision > 0 && cur.precision() > precision) {
            int drop = cur.precision() - precision;
            cur = new Dec(divRound(cur.unscaled, pow10(drop), mode), Math.subtractExact(cur.scale, drop));
        }
        return cur;
    }

    // ---- division and roots ----------------------------------------------------------------

    /** Quotient rounded to {@code precision} significant digits; see TASK.md for the preferred-scale rule. */
    public Dec divide(Dec d, int precision, RoundingMode mode) {
        Objects.requireNonNull(mode);
        if (precision <= 0) {
            throw new IllegalArgumentException("precision must be positive");
        }
        if (d.signum() == 0) {
            throw new ArithmeticException(signum() == 0 ? "Division undefined" : "Division by zero");
        }
        int preferred = Math.subtractExact(scale, d.scale);
        if (signum() == 0) {
            return new Dec(BigInteger.ZERO, preferred);
        }
        int sign = signum() * d.signum();
        BigInteger a = unscaled.abs();
        BigInteger b = d.unscaled.abs();
        // value = a/b * 10^-preferred. digits(a/b) is da-db or da-db+1, so k below gives p or p+1 quotient digits.
        int k = precision - (digits(a) - digits(b));
        BigInteger[] qr = scaledDivide(a, b, k);
        if (digits(qr[0]) > precision) {
            k--;
            qr = scaledDivide(a, b, k);
        }
        BigInteger q = qr[0];
        boolean exact = qr[1].signum() == 0;
        long newScale = (long) preferred + k;
        if (!exact) {
            // Round using the true remainder: compare 2*r against the divisor (scaled the same way as the numerator).
            BigInteger den = k < 0 ? b.multiply(pow10(-k)) : b;
            q = applyRounding(q, qr[1], den, sign, mode);
        }
        Dec res = new Dec(sign < 0 ? q.negate() : q, checkScale(newScale));
        if (res.precision() > precision) { // carry, e.g. 0.999.. -> 1.000..
            res = new Dec(res.unscaled.divide(BigInteger.TEN), checkScale(newScale - 1));
        }
        return exact ? res.stripTo(preferred) : res;
    }

    /** Exact quotient at the smallest scale &gt;= the preferred scale; throws if it does not terminate. */
    public Dec divideExact(Dec d) {
        if (d.signum() == 0) {
            throw new ArithmeticException(signum() == 0 ? "Division undefined" : "Division by zero");
        }
        int preferred = Math.subtractExact(scale, d.scale);
        if (signum() == 0) {
            return new Dec(BigInteger.ZERO, preferred);
        }
        // a/b terminates iff b/gcd(a,b) has only the prime factors 2 and 5.
        BigInteger a = unscaled.abs();
        BigInteger b = d.unscaled.abs();
        BigInteger den = b.divide(a.gcd(b));
        int twos = den.getLowestSetBit();
        den = den.shiftRight(twos);
        int fives = 0;
        BigInteger five = BigInteger.valueOf(5);
        while (den.mod(five).signum() == 0) {
            den = den.divide(five);
            fives++;
        }
        if (!den.equals(BigInteger.ONE)) {
            throw new ArithmeticException("Non-terminating decimal expansion; no exact representable decimal result.");
        }
        int k = Math.max(twos, fives); // a*10^k/b is an integer
        BigInteger q = a.multiply(pow10(k)).divide(b);
        Dec res = new Dec((signum() * d.signum()) < 0 ? q.negate() : q, checkScale((long) preferred + k));
        return res.stripTo(preferred);
    }

    /** Square root correctly rounded to {@code precision} digits (exact roots stripped toward scale/2). */
    public Dec sqrt(int precision, RoundingMode mode) {
        Objects.requireNonNull(mode);
        if (precision <= 0) {
            throw new IllegalArgumentException("precision must be positive");
        }
        if (signum() < 0) {
            throw new ArithmeticException("Attempted square root of negative number");
        }
        int preferred = scale / 2; // Java int division truncates toward zero, as BigDecimal does
        if (signum() == 0) {
            return new Dec(BigInteger.ZERO, preferred);
        }
        BigInteger u = unscaled;
        long s = scale;
        if ((s & 1) != 0) { // make the scale even so the root has an integral scale
            u = u.multiply(BigInteger.TEN);
            s++;
        }
        long half = s / 2;
        // sqrt(value) = sqrt(u * 100^j) * 10^-(half + j); choose j so the integer root has `precision` digits.
        // For j < 0 the radicand is the fraction u / 100^-j, kept as num/den so no information is lost.
        int j = precision - (digits(u) + 1) / 2;
        BigInteger[] nd = radicand(u, j);
        BigInteger r = nd[0].divide(nd[1]).sqrt();
        while (digits(r) != precision) {
            j += digits(r) > precision ? -1 : 1;
            nd = radicand(u, j);
            r = nd[0].divide(nd[1]).sqrt();
        }
        BigInteger num = nd[0];
        BigInteger den = nd[1];
        long resScale = half + j;
        if (num.equals(r.multiply(r).multiply(den))) { // exact root
            return new Dec(r, checkScale(resScale)).stripTo(preferred);
        }
        // Compare sqrt(num/den) with r + 1/2:  4*num versus (2r+1)^2 * den.
        BigInteger twoRPlusOne = r.shiftLeft(1).add(BigInteger.ONE);
        int cmpHalf = num.shiftLeft(2).compareTo(twoRPlusOne.multiply(twoRPlusOne).multiply(den));
        boolean up = switch (mode) {
            case UP, CEILING -> true;
            case DOWN, FLOOR -> false;
            case HALF_UP -> cmpHalf >= 0;
            case HALF_DOWN -> cmpHalf > 0;
            case HALF_EVEN -> cmpHalf > 0 || (cmpHalf == 0 && r.testBit(0));
            case UNNECESSARY -> throw new ArithmeticException("Rounding necessary");
        };
        if (up) {
            r = r.add(BigInteger.ONE);
        }
        Dec res = new Dec(r, checkScale(resScale));
        if (res.precision() > precision) {
            res = new Dec(res.unscaled.divide(BigInteger.TEN), checkScale(resScale - 1));
        }
        return res;
    }

    /** {num, den} with num/den = u * 100^j. */
    private static BigInteger[] radicand(BigInteger u, int j) {
        return j >= 0
                ? new BigInteger[] {u.multiply(pow10(2 * j)), BigInteger.ONE}
                : new BigInteger[] {u, pow10(-2 * j)};
    }

    // ---- normalisation and comparison ------------------------------------------------------

    public Dec stripTrailingZeros() {
        if (signum() == 0) {
            return new Dec(BigInteger.ZERO, 0);
        }
        return stripTo(Integer.MIN_VALUE);
    }

    @Override
    public int compareTo(Dec o) {
        int s = Math.max(scale, o.scale);
        return raise(unscaled, s - scale).compareTo(raise(o.unscaled, s - o.scale));
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Dec o && scale == o.scale && unscaled.equals(o.unscaled);
    }

    @Override
    public int hashCode() {
        return 31 * unscaled.hashCode() + scale;
    }

    // ---- text ------------------------------------------------------------------------------

    @Override
    public String toString() {
        String coeff = unscaled.abs().toString();
        String sign = unscaled.signum() < 0 ? "-" : "";
        long adjusted = -(long) scale + (coeff.length() - 1);
        if (scale >= 0 && adjusted >= -6) {
            return sign + plain(coeff);
        }
        StringBuilder sb = new StringBuilder(sign).append(coeff.charAt(0));
        if (coeff.length() > 1) {
            sb.append('.').append(coeff, 1, coeff.length());
        }
        if (adjusted != 0) {
            sb.append('E').append(adjusted > 0 ? "+" : "").append(adjusted);
        }
        return sb.toString();
    }

    public String toPlainString() {
        if (signum() == 0) {
            return scale > 0 ? "0." + "0".repeat(scale) : "0";
        }
        String sign = unscaled.signum() < 0 ? "-" : "";
        String coeff = unscaled.abs().toString();
        if (scale <= 0) {
            return sign + coeff + "0".repeat(-scale);
        }
        return sign + plain(coeff);
    }

    /** Coefficient with the decimal point inserted for a non-negative scale. */
    private String plain(String coeff) {
        if (scale == 0) {
            return coeff;
        }
        int len = coeff.length();
        if (len > scale) {
            return coeff.substring(0, len - scale) + "." + coeff.substring(len - scale);
        }
        return "0." + "0".repeat(scale - len) + coeff;
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Strip trailing zeros while scale &gt; target. */
    private Dec stripTo(int target) {
        BigInteger u = unscaled;
        long s = scale;
        while (s > target) {
            BigInteger[] qr = u.divideAndRemainder(BigInteger.TEN);
            if (qr[1].signum() != 0) {
                break;
            }
            u = qr[0];
            s--;
        }
        return new Dec(u, (int) s);
    }

    private static int checkScale(long s) {
        if (s > Integer.MAX_VALUE || s < Integer.MIN_VALUE) {
            throw new ArithmeticException("scale overflow");
        }
        return (int) s;
    }

    private static int digits(BigInteger v) {
        return v.signum() == 0 ? 1 : v.abs().toString().length();
    }

    private static BigInteger pow10(int n) {
        return BigInteger.TEN.pow(n);
    }

    private static BigInteger raise(BigInteger v, int powerOfTen) {
        return powerOfTen == 0 ? v : v.multiply(pow10(powerOfTen));
    }

    /** floor(a * 10^k / b) and its remainder (for negative k the divisor is multiplied instead). */
    private static BigInteger[] scaledDivide(BigInteger a, BigInteger b, int k) {
        return k >= 0 ? a.multiply(pow10(k)).divideAndRemainder(b) : a.divideAndRemainder(b.multiply(pow10(-k)));
    }

    private static BigInteger divRound(BigInteger num, BigInteger den, RoundingMode mode) {
        BigInteger[] qr = num.divideAndRemainder(den);
        if (qr[1].signum() == 0) {
            return qr[0];
        }
        int sign = num.signum() * den.signum();
        BigInteger mag = applyRounding(qr[0].abs(), qr[1].abs(), den.abs(), sign, mode);
        return sign < 0 ? mag.negate() : mag;
    }

    /** Round the magnitude q (with non-zero magnitude remainder rem of division by den) for a result of the given sign. */
    private static BigInteger applyRounding(BigInteger q, BigInteger rem, BigInteger den, int sign, RoundingMode mode) {
        int cmpHalf = rem.shiftLeft(1).compareTo(den);
        boolean up = switch (mode) {
            case UP -> true;
            case DOWN -> false;
            case CEILING -> sign > 0;
            case FLOOR -> sign < 0;
            case HALF_UP -> cmpHalf >= 0;
            case HALF_DOWN -> cmpHalf > 0;
            case HALF_EVEN -> cmpHalf > 0 || (cmpHalf == 0 && q.testBit(0));
            case UNNECESSARY -> throw new ArithmeticException("Rounding necessary");
        };
        return up ? q.add(BigInteger.ONE) : q;
    }
}
