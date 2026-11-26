package ae.linalg;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Characteristic polynomial, rational eigenvalues and eigenspaces. */
public final class CharPoly {
    private CharPoly() { }

    /**
     * Faddeev-LeVerrier: coefficients c[0..n] of det(xI - A) = sum c[k] x^k with c[n] = 1.
     * Recurrence: M_1 = I, c[n-k] = -tr(A M_k) / k, M_{k+1} = A M_k + c[n-k] I.
     */
    public static Rational[] coefficients(Matrix a) {
        a.requireSquare();
        int n = a.rows();
        Rational[] c = new Rational[n + 1];
        c[n] = Rational.ONE;
        Matrix mk = Matrix.identity(n);
        for (int k = 1; k <= n; k++) {
            Rational t = a.times(mk).trace();
            c[n - k] = t.negate().div(Rational.of(k));
            mk = a.times(mk).plus(Matrix.identity(n).times(c[n - k]));
        }
        return c;
    }

    /**
     * Distinct rational eigenvalues in ascending order mapped to algebraic multiplicity. Uses the rational-root
     * theorem on the integer-scaled polynomial; the constant and leading coefficients must fit in 62 bits.
     */
    public static Map<Rational, Integer> rationalEigenvalues(Matrix a) {
        Rational[] c = coefficients(a);
        Map<Rational, Integer> result = new TreeMap<>();
        int low = 0;
        while (low < c.length && c[low].isZero()) {
            low++;
        }
        if (low > 0) {
            result.put(Rational.ZERO, low);
        }
        // integer polynomial without the x^low factor
        BigInteger lcm = BigInteger.ONE;
        for (int k = low; k < c.length; k++) {
            lcm = lcm.divide(lcm.gcd(c[k].den())).multiply(c[k].den());
        }
        BigInteger constant = c[low].mul(Rational.of(lcm, BigInteger.ONE)).num();
        BigInteger lead = c[c.length - 1].mul(Rational.of(lcm, BigInteger.ONE)).num();
        if (c.length - low <= 1) {
            return result;
        }
        TreeSet<Rational> candidates = new TreeSet<>();
        for (long p : divisors(constant.abs())) {
            for (long q : divisors(lead.abs())) {
                candidates.add(Rational.of(p, q));
                candidates.add(Rational.of(-p, q));
            }
        }
        List<Rational> poly = new ArrayList<>();
        for (int k = low; k < c.length; k++) {
            poly.add(c[k]);
        }
        for (Rational cand : candidates) {
            int mult = 0;
            while (poly.size() > 1) {
                List<Rational> q = divideByLinear(poly, cand);
                if (q == null) {
                    break;
                }
                poly = q;
                mult++;
            }
            if (mult > 0) {
                result.put(cand, mult);
            }
        }
        return result;
    }

    /** Synthetic division of sum poly[k] x^k by (x - r); returns the quotient or null if the remainder is non-zero. */
    private static List<Rational> divideByLinear(List<Rational> poly, Rational r) {
        int deg = poly.size() - 1;
        Rational[] q = new Rational[deg];
        Rational carry = Rational.ZERO;
        for (int k = deg; k >= 1; k--) {
            carry = poly.get(k).add(carry.mul(r));
            q[k - 1] = carry;
        }
        Rational remainder = poly.get(0).add(carry.mul(r));
        if (!remainder.isZero()) {
            return null;
        }
        return List.of(q);
    }

    private static List<Long> divisors(BigInteger n) {
        long v = n.longValueExact();
        if (v <= 0 || n.bitLength() > 62) {
            throw new ArithmeticException("coefficient out of supported range: " + n);
        }
        List<Long> out = new ArrayList<>();
        for (long d = 1; d * d <= v; d++) {
            if (v % d == 0) {
                out.add(d);
                if (d != v / d) {
                    out.add(v / d);
                }
            }
        }
        return out;
    }

    /** Null space of A - lambda I (empty unless lambda is an eigenvalue). */
    public static List<Rational[]> eigenspace(Matrix a, Rational lambda) {
        a.requireSquare();
        return Elimination.nullspace(a.minus(Matrix.identity(a.rows()).times(lambda)));
    }
}
