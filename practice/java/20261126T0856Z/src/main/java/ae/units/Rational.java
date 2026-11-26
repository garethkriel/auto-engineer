package ae.units;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Objects;

/** Immutable exact fraction. The denominator is always positive and the fraction is in lowest terms. */
public final class Rational implements Comparable<Rational> {

    /** Largest decimal exponent accepted by {@link #parseDecimal}; bounds the size of the BigInteger built. */
    private static final int MAX_DECIMAL_SCALE = 1000;

    private static final long MAX_POW_BITS = 1L << 20;

    private static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    private static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    private final BigInteger num;
    private final BigInteger den;

    private Rational(BigInteger num, BigInteger den) {
        this.num = num;
        this.den = den;
    }

    /** The integer {@code n}. */
    public static Rational of(long n) {
        return of(BigInteger.valueOf(n), BigInteger.ONE);
    }

    /** {@code n / d}; throws {@link ArithmeticException} when {@code d} is zero. */
    public static Rational of(long n, long d) {
        return of(BigInteger.valueOf(n), BigInteger.valueOf(d));
    }

    /** {@code n / d} in lowest terms with a positive denominator. */
    public static Rational of(BigInteger n, BigInteger d) {
        Objects.requireNonNull(n, "n");
        Objects.requireNonNull(d, "d");
        if (d.signum() == 0) {
            throw new ArithmeticException("zero denominator");
        }
        if (n.signum() == 0) {
            return ZERO;
        }
        BigInteger g = n.gcd(d);
        BigInteger nn = n.divide(g);
        BigInteger dd = d.divide(g);
        if (dd.signum() < 0) {
            nn = nn.negate();
            dd = dd.negate();
        }
        if (nn.equals(BigInteger.ONE) && dd.equals(BigInteger.ONE)) {
            return ONE;
        }
        return new Rational(nn, dd);
    }

    /**
     * Parses a decimal literal such as {@code -12.5}, {@code .5}, {@code 3e2} or {@code 1.5E-3} exactly.
     *
     * @throws NumberFormatException on malformed text
     * @throws IllegalArgumentException when the decimal exponent magnitude exceeds 1000
     */
    public static Rational parseDecimal(String text) {
        Objects.requireNonNull(text, "text");
        BigDecimal d = new BigDecimal(text);
        long scale = d.scale();
        if (Math.abs(scale) > MAX_DECIMAL_SCALE) {
            throw new IllegalArgumentException("decimal exponent out of range: " + text);
        }
        BigInteger unscaled = d.unscaledValue();
        if (scale >= 0) {
            return of(unscaled, BigInteger.TEN.pow((int) scale));
        }
        return of(unscaled.multiply(BigInteger.TEN.pow((int) -scale)), BigInteger.ONE);
    }

    public BigInteger numerator() {
        return num;
    }

    public BigInteger denominator() {
        return den;
    }

    public Rational plus(Rational o) {
        return of(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    public Rational minus(Rational o) {
        return of(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
    }

    public Rational times(Rational o) {
        return of(num.multiply(o.num), den.multiply(o.den));
    }

    /** Throws {@link ArithmeticException} when {@code o} is zero. */
    public Rational dividedBy(Rational o) {
        if (o.num.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        return of(num.multiply(o.den), den.multiply(o.num));
    }

    public Rational negate() {
        return of(num.negate(), den);
    }

    /**
     * Integer power; a negative exponent of zero throws {@link ArithmeticException}. A result that
     * would need more than about a million bits (or {@code Integer.MIN_VALUE}) also throws
     * {@link ArithmeticException}, so hostile exponents cannot exhaust memory.
     */
    public Rational pow(int n) {
        if (n == 0) {
            return ONE;
        }
        if (n == Integer.MIN_VALUE) {
            throw new ArithmeticException("exponent out of range");
        }
        long estimatedBits = (long) Math.max(num.bitLength(), den.bitLength()) * Math.abs((long) n);
        if (estimatedBits > MAX_POW_BITS) {
            throw new ArithmeticException("power result too large");
        }
        if (n > 0) {
            return of(num.pow(n), den.pow(n));
        }
        if (num.signum() == 0) {
            throw new ArithmeticException("zero to a negative power");
        }
        return of(den.pow(-n), num.pow(-n));
    }

    public int signum() {
        return num.signum();
    }

    @Override
    public int compareTo(Rational o) {
        return num.multiply(o.den).compareTo(o.num.multiply(den));
    }

    /** Nearest double (via 20 significant decimal digits, so huge parts cannot overflow to NaN). */
    public double doubleValue() {
        return new BigDecimal(num).divide(new BigDecimal(den), new MathContext(20)).doubleValue();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rational r && num.equals(r.num) && den.equals(r.den);
    }

    @Override
    public int hashCode() {
        return 31 * num.hashCode() + den.hashCode();
    }

    @Override
    public String toString() {
        return den.equals(BigInteger.ONE) ? num.toString() : num + "/" + den;
    }
}
