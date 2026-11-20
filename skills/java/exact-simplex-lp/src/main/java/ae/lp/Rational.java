package ae.lp;

import java.math.BigInteger;

/** Immutable exact fraction. Always normalised: gcd 1, positive denominator. */
public final class Rational implements Comparable<Rational> {
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    private final BigInteger num;
    private final BigInteger den;

    private Rational(BigInteger num, BigInteger den) {
        this.num = num;
        this.den = den;
    }

    public static Rational of(long n) {
        return of(BigInteger.valueOf(n), BigInteger.ONE);
    }

    public static Rational of(long n, long d) {
        return of(BigInteger.valueOf(n), BigInteger.valueOf(d));
    }

    /** @throws ArithmeticException when {@code d} is zero */
    public static Rational of(BigInteger n, BigInteger d) {
        if (d.signum() == 0) {
            throw new ArithmeticException("zero denominator");
        }
        if (n.signum() == 0) {
            return ZERO;
        }
        if (d.signum() < 0) {
            n = n.negate();
            d = d.negate();
        }
        BigInteger g = n.gcd(d);
        return new Rational(n.divide(g), d.divide(g));
    }

    /** Parses {@code int} or {@code int/int} with an optional leading minus. */
    public static Rational parse(String text) {
        int slash = text.indexOf('/');
        try {
            if (slash < 0) {
                return of(new BigInteger(text), BigInteger.ONE);
            }
            return of(new BigInteger(text.substring(0, slash)), new BigInteger(text.substring(slash + 1)));
        } catch (NumberFormatException e) {
            throw new NumberFormatException("not a rational: '" + text + "'");
        }
    }

    public Rational add(Rational o) {
        return of(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    public Rational sub(Rational o) {
        return add(o.negate());
    }

    public Rational mul(Rational o) {
        return of(num.multiply(o.num), den.multiply(o.den));
    }

    public Rational div(Rational o) {
        if (o.num.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        return of(num.multiply(o.den), den.multiply(o.num));
    }

    public Rational negate() {
        return new Rational(num.negate(), den);
    }

    public int signum() {
        return num.signum();
    }

    public boolean isZero() {
        return num.signum() == 0;
    }

    /** Exact dot product. */
    public static Rational dot(Rational[] a, Rational[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("length mismatch: " + a.length + " vs " + b.length);
        }
        Rational sum = ZERO;
        for (int i = 0; i < a.length; i++) {
            sum = sum.add(a[i].mul(b[i]));
        }
        return sum;
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
