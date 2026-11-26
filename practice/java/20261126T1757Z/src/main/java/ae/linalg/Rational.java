package ae.linalg;

import java.math.BigInteger;
import java.util.Objects;

/** Immutable exact fraction. Invariant: {@code den > 0} and {@code gcd(|num|, den) == 1}. */
public final class Rational implements Comparable<Rational> {
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    private final BigInteger num;
    private final BigInteger den;

    private Rational(BigInteger num, BigInteger den) {
        this.num = num;
        this.den = den;
    }

    /** Single normalising factory; every other operation goes through it. */
    public static Rational of(BigInteger n, BigInteger d) {
        Objects.requireNonNull(n);
        Objects.requireNonNull(d);
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
        return new Rational(nn, dd);
    }

    public static Rational of(long n, long d) {
        return of(BigInteger.valueOf(n), BigInteger.valueOf(d));
    }

    public static Rational of(long n) {
        return of(BigInteger.valueOf(n), BigInteger.ONE);
    }

    public BigInteger num() {
        return num;
    }

    public BigInteger den() {
        return den;
    }

    public boolean isZero() {
        return num.signum() == 0;
    }

    public int signum() {
        return num.signum();
    }

    public Rational add(Rational o) {
        return of(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    public Rational sub(Rational o) {
        return of(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
    }

    public Rational mul(Rational o) {
        return of(num.multiply(o.num), den.multiply(o.den));
    }

    public Rational div(Rational o) {
        if (o.isZero()) {
            throw new ArithmeticException("division by zero");
        }
        return of(num.multiply(o.den), den.multiply(o.num));
    }

    public Rational negate() {
        return new Rational(num.negate(), den);
    }

    public Rational inverse() {
        return ONE.div(this);
    }

    /** Integer power; negative exponents invert first, so {@code ZERO.pow(-1)} throws. */
    public Rational pow(int e) {
        if (e < 0) {
            return inverse().pow(-e);
        }
        Rational result = ONE;
        Rational base = this;
        int k = e;
        while (k > 0) {
            if ((k & 1) == 1) {
                result = result.mul(base);
            }
            base = base.mul(base);
            k >>= 1;
        }
        return result;
    }

    @Override
    public int compareTo(Rational o) {
        return num.multiply(o.den).compareTo(o.num.multiply(den));
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
