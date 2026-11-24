package ae.polyfp;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Immutable polynomial over GF(p) for a prime {@code p < 2^31}. Coefficients are stored low to high, reduced
 * into {@code [0, p)} and without trailing zeros (the zero polynomial has no coefficients).
 */
public final class Poly implements Comparable<Poly> {
    private static final long MAX_P = 1L << 31;

    private final long p;
    private final long[] c;

    private Poly(long p, long[] normalised) {
        this.p = p;
        this.c = normalised;
    }

    static void requirePrime(long p) {
        if (p < 2 || p >= MAX_P || !BigInteger.valueOf(p).isProbablePrime(40)) {
            throw new IllegalArgumentException("modulus must be a prime below 2^31: " + p);
        }
    }

    private static Poly make(long p, long[] raw) {
        int n = raw.length;
        while (n > 0 && raw[n - 1] == 0) {
            n--;
        }
        return new Poly(p, n == raw.length ? raw : Arrays.copyOf(raw, n));
    }

    public static Poly of(long p, long... coeffs) {
        requirePrime(p);
        long[] r = new long[coeffs.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = Math.floorMod(coeffs[i], p);
        }
        return make(p, r);
    }

    public static Poly zero(long p) {
        return of(p);
    }

    public static Poly one(long p) {
        return of(p, 1);
    }

    public static Poly x(long p) {
        return of(p, 0, 1);
    }

    public static Poly monomial(long p, long coef, int degree) {
        if (degree < 0) {
            throw new IllegalArgumentException("negative degree");
        }
        requirePrime(p);
        long[] r = new long[degree + 1];
        r[degree] = Math.floorMod(coef, p);
        return make(p, r);
    }

    public long modulus() {
        return p;
    }

    public int degree() {
        return c.length - 1;
    }

    public boolean isZero() {
        return c.length == 0;
    }

    public long coeff(int i) {
        return i >= 0 && i < c.length ? c[i] : 0;
    }

    /** Leading coefficient, 0 for the zero polynomial. */
    public long lead() {
        return c.length == 0 ? 0 : c[c.length - 1];
    }

    private void same(Poly o) {
        if (p != o.p) {
            throw new IllegalArgumentException("different fields: " + p + " vs " + o.p);
        }
    }

    public Poly add(Poly o) {
        same(o);
        long[] r = new long[Math.max(c.length, o.c.length)];
        for (int i = 0; i < r.length; i++) {
            r[i] = (coeff(i) + o.coeff(i)) % p;
        }
        return make(p, r);
    }

    public Poly neg() {
        long[] r = new long[c.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = (p - c[i]) % p;
        }
        return make(p, r);
    }

    public Poly sub(Poly o) {
        same(o);
        return add(o.neg());
    }

    public Poly mul(long k) {
        long m = Math.floorMod(k, p);
        long[] r = new long[c.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = c[i] * m % p;
        }
        return make(p, r);
    }

    public Poly mul(Poly o) {
        same(o);
        if (isZero() || o.isZero()) {
            return zero(p);
        }
        long[] r = new long[c.length + o.c.length - 1];
        for (int i = 0; i < c.length; i++) {
            for (int j = 0; j < o.c.length; j++) {
                r[i + j] = (r[i + j] + c[i] * o.c[j]) % p;
            }
        }
        return make(p, r);
    }

    static long inv(long a, long p) {
        return BigInteger.valueOf(a).modInverse(BigInteger.valueOf(p)).longValueExact();
    }

    /** Returns {quotient, remainder} with {@code this = q*d + r} and {@code deg r < deg d}. */
    public Poly[] divMod(Poly d) {
        same(d);
        if (d.isZero()) {
            throw new IllegalArgumentException("division by zero polynomial");
        }
        long[] r = c.clone();
        int dd = d.degree();
        int qd = degree() - dd;
        if (qd < 0) {
            return new Poly[] {zero(p), this};
        }
        long[] q = new long[qd + 1];
        long leadInv = inv(d.lead(), p);
        for (int i = qd; i >= 0; i--) {
            long coef = r[i + dd] * leadInv % p;
            q[i] = coef;
            if (coef != 0) {
                for (int j = 0; j <= dd; j++) {
                    r[i + j] = Math.floorMod(r[i + j] - coef * d.c[j], p);
                }
            }
        }
        return new Poly[] {make(p, q), make(p, Arrays.copyOf(r, dd))};
    }

    public Poly mod(Poly d) {
        return divMod(d)[1];
    }

    public Poly monic() {
        return isZero() ? this : mul(inv(lead(), p));
    }

    /** Monic greatest common divisor; {@code gcd(0, 0) = 0}. */
    public static Poly gcd(Poly a, Poly b) {
        a.same(b);
        Poly x = a;
        Poly y = b;
        while (!y.isZero()) {
            Poly r = x.mod(y);
            x = y;
            y = r;
        }
        return x.monic();
    }

    /** {@code this^e mod m} by square-and-multiply. */
    public Poly powMod(BigInteger e, Poly m) {
        same(m);
        if (e.signum() < 0) {
            throw new IllegalArgumentException("negative exponent");
        }
        Poly result = one(p).mod(m);
        Poly base = mod(m);
        for (int i = e.bitLength() - 1; i >= 0; i--) {
            result = result.mul(result).mod(m);
            if (e.testBit(i)) {
                result = result.mul(base).mod(m);
            }
        }
        return result;
    }

    public long eval(long x) {
        long xm = Math.floorMod(x, p);
        long acc = 0;
        for (int i = c.length - 1; i >= 0; i--) {
            acc = (acc * xm + c[i]) % p;
        }
        return acc;
    }

    public Poly derivative() {
        if (c.length <= 1) {
            return zero(p);
        }
        long[] r = new long[c.length - 1];
        for (int i = 1; i < c.length; i++) {
            r[i - 1] = c[i] * (i % p) % p;
        }
        return make(p, r);
    }

    /** The unique polynomial of degree below {@code xs.length} through the points (distinct xs mod p). */
    public static Poly interpolate(long p, long[] xs, long[] ys) {
        requirePrime(p);
        if (xs.length != ys.length) {
            throw new IllegalArgumentException("length mismatch");
        }
        int n = xs.length;
        long[] x = new long[n];
        for (int i = 0; i < n; i++) {
            x[i] = Math.floorMod(xs[i], p);
            for (int j = 0; j < i; j++) {
                if (x[i] == x[j]) {
                    throw new IllegalArgumentException("duplicate x");
                }
            }
        }
        Poly total = zero(p);
        for (int i = 0; i < n; i++) {
            Poly num = one(p);
            long den = 1;
            for (int j = 0; j < n; j++) {
                if (j != i) {
                    num = num.mul(of(p, -x[j], 1));
                    den = den * Math.floorMod(x[i] - x[j], p) % p;
                }
            }
            total = total.add(num.mul(Math.floorMod(ys[i], p) * inv(den, p) % p));
        }
        return total;
    }

    /** Rabin's irreducibility test. Degree 0 and the zero polynomial are not irreducible. */
    public boolean isIrreducible() {
        int n = degree();
        if (n < 1) {
            return false;
        }
        if (n == 1) {
            return true;
        }
        Poly f = monic();
        Poly x = x(p).mod(f);
        BigInteger bp = BigInteger.valueOf(p);
        if (!x.powMod(bp.pow(n), f).equals(x)) {
            return false;
        }
        for (int q = 2; q <= n; q++) {
            if (n % q == 0 && BigInteger.valueOf(q).isProbablePrime(40)) {
                Poly h = x.powMod(bp.pow(n / q), f).sub(x);
                if (gcd(h, f).degree() != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Full factorisation over GF(p); odd p only. */
    public Factorization factor() {
        if (isZero()) {
            throw new IllegalArgumentException("cannot factor zero");
        }
        if (p == 2) {
            throw new IllegalArgumentException("factor() supports odd primes only");
        }
        Map<Poly, Integer> found = new LinkedHashMap<>();
        Random rng = new Random(0x5EEDL);
        squarefreeParts(monic(), 1, found, rng);
        List<Factor> out = new ArrayList<>();
        for (Map.Entry<Poly, Integer> e : found.entrySet()) {
            out.add(new Factor(e.getKey(), e.getValue()));
        }
        out.sort(Comparator.comparing(Factor::poly));
        return new Factorization(lead(), out);
    }

    /** Adds the irreducible factors of monic {@code f} (each with multiplicity scaled by {@code scale}). */
    private void squarefreeParts(Poly f, int scale, Map<Poly, Integer> found, Random rng) {
        if (f.degree() < 1) {
            return;
        }
        Poly cc = gcd(f, f.derivative());
        Poly w = f.divMod(cc)[0];
        int i = 1;
        while (w.degree() > 0) {
            Poly y = gcd(w, cc);
            Poly z = w.divMod(y)[0];
            if (z.degree() > 0) {
                for (Poly g : distinctDegree(z, rng)) {
                    found.merge(g, i * scale, Integer::sum);
                }
            }
            i++;
            w = y;
            cc = cc.divMod(y)[0];
        }
        if (cc.degree() > 0) {
            // cc has zero derivative, so it is g(x^p); a^p = a in GF(p) lets us take the p-th root.
            long[] r = new long[cc.degree() / (int) p + 1];
            for (int k = 0; k < r.length; k++) {
                r[k] = cc.coeff(k * (int) p);
            }
            squarefreeParts(make(p, r), scale * (int) p, found, rng);
        }
    }

    /** Splits a monic squarefree polynomial into its monic irreducible factors. */
    private List<Poly> distinctDegree(Poly f, Random rng) {
        List<Poly> out = new ArrayList<>();
        Poly rest = f;
        Poly h = x(p);
        BigInteger bp = BigInteger.valueOf(p);
        for (int d = 1; rest.degree() >= 2 * d; d++) {
            h = h.powMod(bp, rest);
            Poly g = gcd(h.sub(x(p)), rest);
            if (g.degree() > 0) {
                equalDegree(g, d, rng, out);
                rest = rest.divMod(g)[0];
                h = h.mod(rest);
            }
        }
        if (rest.degree() > 0) {
            out.add(rest);
        }
        return out;
    }

    /** Cantor-Zassenhaus: g is a product of distinct irreducibles of degree d. */
    private void equalDegree(Poly g, int d, Random rng, List<Poly> out) {
        if (g.degree() == d) {
            out.add(g);
            return;
        }
        BigInteger exp = BigInteger.valueOf(p).pow(d).subtract(BigInteger.ONE).shiftRight(1);
        while (true) {
            long[] r = new long[g.degree()];
            for (int i = 0; i < r.length; i++) {
                r[i] = rng.nextLong(p);
            }
            Poly a = make(p, r);
            if (a.degree() < 1) {
                continue;
            }
            Poly s = gcd(a.powMod(exp, g).sub(one(p)), g);
            if (s.degree() > 0 && s.degree() < g.degree()) {
                equalDegree(s, d, rng, out);
                equalDegree(g.divMod(s)[0], d, rng, out);
                return;
            }
        }
    }

    /** Order by degree, then coefficients from the highest down. */
    @Override
    public int compareTo(Poly o) {
        if (c.length != o.c.length) {
            return Integer.compare(c.length, o.c.length);
        }
        for (int i = c.length - 1; i >= 0; i--) {
            if (c[i] != o.c[i]) {
                return Long.compare(c[i], o.c[i]);
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Poly q && q.p == p && Arrays.equals(q.c, c);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(c) * 31 + Long.hashCode(p);
    }

    @Override
    public String toString() {
        if (isZero()) {
            return "0 (mod " + p + ")";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = c.length - 1; i >= 0; i--) {
            if (c[i] != 0) {
                if (sb.length() > 0) {
                    sb.append(" + ");
                }
                sb.append(c[i]);
                if (i > 0) {
                    sb.append("x^").append(i);
                }
            }
        }
        return sb.append(" (mod ").append(p).append(')').toString();
    }
}
