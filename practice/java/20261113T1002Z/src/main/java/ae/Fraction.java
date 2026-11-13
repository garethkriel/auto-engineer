package ae;

import java.math.BigInteger;
import java.util.Objects;

/** Immutable exact fraction, always stored reduced with a positive denominator. */
public final class Fraction implements Comparable<Fraction> {
    public static final Fraction ZERO = new Fraction(0, 1);
    public static final Fraction ONE = new Fraction(1, 1);

    private final long num;
    private final long den;

    private Fraction(long num, long den) {
        this.num = num;
        this.den = den;
    }

    /** Creates a reduced fraction; throws if den is 0 or normalisation overflows. */
    public static Fraction of(long num, long den) {
        if (den == 0) {
            throw new IllegalArgumentException("zero denominator");
        }
        if (num == 0) {
            return ZERO;
        }
        if (den < 0) {
            num = Math.negateExact(num);
            den = Math.negateExact(den);
        }
        long g = gcd(Math.abs(num), den);
        return new Fraction(num / g, den / g);
    }

    /** Parses "a/b" or "a". */
    public static Fraction parse(String s) {
        Objects.requireNonNull(s, "s");
        String[] parts = s.trim().split("/", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("bad fraction: " + s);
        }
        try {
            long n = Long.parseLong(parts[0]);
            long d = parts.length == 2 ? Long.parseLong(parts[1]) : 1;
            return of(n, d);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad fraction: " + s, e);
        }
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    public long numerator() { return num; }

    public long denominator() { return den; }

    public Fraction plus(Fraction o) {
        return of(Math.addExact(Math.multiplyExact(num, o.den), Math.multiplyExact(o.num, den)),
                Math.multiplyExact(den, o.den));
    }

    public Fraction minus(Fraction o) {
        return plus(o.negate());
    }

    private Fraction negate() {
        return new Fraction(Math.negateExact(num), den);
    }

    public Fraction times(Fraction o) {
        return of(Math.multiplyExact(num, o.num), Math.multiplyExact(den, o.den));
    }

    public Fraction dividedBy(Fraction o) {
        if (o.num == 0) {
            throw new ArithmeticException("division by zero");
        }
        return of(Math.multiplyExact(num, o.den), Math.multiplyExact(den, o.num));
    }

    @Override
    public int compareTo(Fraction o) {
        return BigInteger.valueOf(num).multiply(BigInteger.valueOf(o.den))
                .compareTo(BigInteger.valueOf(o.num).multiply(BigInteger.valueOf(den)));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Fraction f && f.num == num && f.den == den;
    }

    @Override
    public int hashCode() {
        return Objects.hash(num, den);
    }

    @Override
    public String toString() {
        return den == 1 ? Long.toString(num) : num + "/" + den;
    }
}
