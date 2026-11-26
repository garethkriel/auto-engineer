package ae.linalg;

import java.util.ArrayList;
import java.util.List;

/** Exact Gram-Schmidt: orthogonal (not normalised) vectors, since norms are generally irrational. */
public final class GramSchmidt {
    private GramSchmidt() { }

    public static Rational dot(Rational[] x, Rational[] y) {
        if (x.length != y.length) {
            throw new IllegalArgumentException("lengths differ: " + x.length + " vs " + y.length);
        }
        Rational s = Rational.ZERO;
        for (int i = 0; i < x.length; i++) {
            s = s.add(x[i].mul(y[i]));
        }
        return s;
    }

    /** Orthogonal basis of the span of the input; vectors dependent on earlier ones are dropped. */
    public static List<Rational[]> orthogonalize(List<Rational[]> vectors) {
        List<Rational[]> out = new ArrayList<>();
        for (Rational[] v : vectors) {
            if (!out.isEmpty() && v.length != out.get(0).length) {
                throw new IllegalArgumentException("lengths differ");
            }
            Rational[] r = v.clone();
            for (Rational[] u : out) {
                Rational f = dot(v, u).div(dot(u, u));
                for (int i = 0; i < r.length; i++) {
                    r[i] = r[i].sub(f.mul(u[i]));
                }
            }
            boolean zero = true;
            for (Rational x : r) {
                zero &= x.isZero();
            }
            if (!zero) {
                out.add(r);
            }
        }
        return out;
    }

    /** Orthogonal projection of v onto the span of an orthogonal basis. */
    public static Rational[] project(Rational[] v, List<Rational[]> orthogonalBasis) {
        Rational[] p = Elimination.zeros(v.length);
        for (Rational[] u : orthogonalBasis) {
            Rational f = dot(v, u).div(dot(u, u));
            for (int i = 0; i < p.length; i++) {
                p[i] = p[i].add(f.mul(u[i]));
            }
        }
        return p;
    }
}
