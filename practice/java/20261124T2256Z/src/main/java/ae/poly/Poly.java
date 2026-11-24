package ae.poly;

import java.util.Arrays;

/** Immutable polynomial over {@link Mod#P}; coefficients low to high, normalised (no trailing zeros). */
public final class Poly {
    /** Below this many terms in the shorter operand multiplication is schoolbook. */
    private static final int NAIVE_LIMIT = 32;

    /** The zero polynomial. */
    public static final Poly ZERO = new Poly(new long[0]);

    /** Quotient and remainder of a division. */
    public record QR(Poly q, Poly r) {}

    private final long[] c;

    private Poly(long[] c) {
        this.c = c;
    }

    /** Builds a polynomial from coefficients (low to high), reducing mod p and trimming trailing zeros. */
    public static Poly of(long... coeffs) {
        long[] t = new long[coeffs.length];
        for (int i = 0; i < t.length; i++) {
            t[i] = Mod.norm(coeffs[i]);
        }
        return trim(t);
    }

    private static Poly trim(long[] t) {
        int n = t.length;
        while (n > 0 && t[n - 1] == 0) {
            n--;
        }
        return new Poly(n == t.length ? t : Arrays.copyOf(t, n));
    }

    /** Degree, or -1 for the zero polynomial. */
    public int degree() {
        return c.length - 1;
    }

    /** Coefficient of x^i (0 beyond the degree). */
    public long coeff(int i) {
        if (i < 0) {
            throw new IllegalArgumentException("negative index");
        }
        return i < c.length ? c[i] : 0;
    }

    public Poly add(Poly o) {
        long[] r = new long[Math.max(c.length, o.c.length)];
        for (int i = 0; i < r.length; i++) {
            r[i] = Mod.add(coeff(i), o.coeff(i));
        }
        return trim(r);
    }

    public Poly sub(Poly o) {
        long[] r = new long[Math.max(c.length, o.c.length)];
        for (int i = 0; i < r.length; i++) {
            r[i] = Mod.sub(coeff(i), o.coeff(i));
        }
        return trim(r);
    }

    /** Product; schoolbook for short operands, NTT otherwise. */
    public Poly mul(Poly o) {
        if (c.length == 0 || o.c.length == 0) {
            return ZERO;
        }
        int outLen = c.length + o.c.length - 1;
        if (Math.min(c.length, o.c.length) < NAIVE_LIMIT) {
            long[] r = new long[outLen];
            for (int i = 0; i < c.length; i++) {
                for (int j = 0; j < o.c.length; j++) {
                    r[i + j] = Mod.add(r[i + j], Mod.mul(c[i], o.c[j]));
                }
            }
            return trim(r);
        }
        int size = Integer.highestOneBit(outLen - 1) << 1;
        if (outLen == 1) {
            size = 1;
        }
        long[] fa = Arrays.copyOf(c, size);
        long[] fb = Arrays.copyOf(o.c, size);
        Ntt.transform(fa, false);
        Ntt.transform(fb, false);
        for (int i = 0; i < size; i++) {
            fa[i] = Mod.mul(fa[i], fb[i]);
        }
        Ntt.transform(fa, true);
        return trim(Arrays.copyOf(fa, outLen));
    }

    /** This polynomial modulo x^n. */
    public Poly truncate(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("negative length");
        }
        return n >= c.length ? this : trim(Arrays.copyOf(c, n));
    }

    public Poly derivative() {
        if (c.length <= 1) {
            return ZERO;
        }
        long[] r = new long[c.length - 1];
        for (int i = 1; i < c.length; i++) {
            r[i - 1] = Mod.mul(c[i], i);
        }
        return trim(r);
    }

    /** Horner evaluation at x. */
    public long eval(long x) {
        long xr = Mod.norm(x);
        long acc = 0;
        for (int i = c.length - 1; i >= 0; i--) {
            acc = Mod.add(Mod.mul(acc, xr), c[i]);
        }
        return acc;
    }

    /**
     * Power-series inverse g with {@code this * g == 1 (mod x^n)}, by Newton iteration
     * g' = g (2 - f g).
     *
     * @throws IllegalArgumentException if n < 1
     * @throws ArithmeticException if the constant term is zero
     */
    public Poly inverse(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be positive");
        }
        if (coeff(0) == 0) {
            throw new ArithmeticException("constant term is zero");
        }
        Poly g = of(Mod.inv(c[0]));
        Poly two = of(2);
        int m = 1;
        while (m < n) {
            m <<= 1;
            g = g.mul(two.sub(truncate(m).mul(g))).truncate(m);
        }
        return g.truncate(n);
    }

    private Poly reversed(int len) {
        long[] r = new long[len];
        for (int i = 0; i < len; i++) {
            r[len - 1 - i] = coeff(i);
        }
        return trim(r);
    }

    /** Euclidean division {@code this = q*d + r} with deg r < deg d. */
    public QR divMod(Poly d) {
        if (d.c.length == 0) {
            throw new ArithmeticException("division by zero polynomial");
        }
        int n = degree();
        int m = d.degree();
        if (n < m) {
            return new QR(ZERO, this);
        }
        int k = n - m + 1;
        Poly qRev = reversed(n + 1).truncate(k).mul(d.reversed(m + 1).inverse(k)).truncate(k);
        Poly q = qRev.reversed(k);
        Poly r = sub(q.mul(d));
        return new QR(q, r);
    }

    /**
     * Lagrange interpolation, O(n^2): the unique polynomial of degree < n through the points.
     *
     * @throws IllegalArgumentException for empty input, length mismatch or repeated x
     */
    public static Poly interpolate(long[] xs, long[] ys) {
        int n = xs.length;
        if (n == 0 || ys.length != n) {
            throw new IllegalArgumentException("need equally many (>= 1) xs and ys");
        }
        long[] x = new long[n];
        for (int i = 0; i < n; i++) {
            x[i] = Mod.norm(xs[i]);
            for (int j = 0; j < i; j++) {
                if (x[i] == x[j]) {
                    throw new IllegalArgumentException("repeated x: " + xs[i]);
                }
            }
        }
        // master = prod (X - x_i)
        long[] master = new long[n + 1];
        master[0] = 1;
        int len = 1;
        for (int i = 0; i < n; i++) {
            for (int j = len; j >= 0; j--) {
                long shifted = j > 0 ? master[j - 1] : 0;
                master[j] = Mod.sub(shifted, Mod.mul(master[j], x[i]));
            }
            len++;
        }
        long[] result = new long[n];
        long[] q = new long[n];
        for (int i = 0; i < n; i++) {
            // q = master / (X - x_i) by synthetic division
            long carry = 0;
            for (int j = n; j >= 1; j--) {
                carry = Mod.add(master[j], Mod.mul(carry, x[i]));
                q[j - 1] = carry;
            }
            long denom = 0;
            for (int j = n - 1; j >= 0; j--) {
                denom = Mod.add(Mod.mul(denom, x[i]), q[j]);
            }
            long w = Mod.mul(Mod.norm(ys[i]), Mod.inv(denom));
            for (int j = 0; j < n; j++) {
                result[j] = Mod.add(result[j], Mod.mul(w, q[j]));
            }
        }
        return trim(result);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Poly p && Arrays.equals(c, p.c);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(c);
    }

    @Override
    public String toString() {
        return Arrays.toString(c);
    }
}
