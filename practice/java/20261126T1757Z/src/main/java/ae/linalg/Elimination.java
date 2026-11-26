package ae.linalg;

import java.util.ArrayList;
import java.util.List;

/** Gauss-Jordan based algorithms over exact rationals. */
public final class Elimination {
    private Elimination() { }

    /** Reduced row echelon form and the pivot column of each non-zero row. */
    public record Rref(Matrix matrix, List<Integer> pivots) { }

    public static Rref rref(Matrix m) {
        Rational[][] g = m.copyGrid();
        List<Integer> pivots = new ArrayList<>();
        int r = 0;
        for (int c = 0; c < m.cols() && r < m.rows(); c++) {
            int p = r;
            while (p < m.rows() && g[p][c].isZero()) {
                p++;
            }
            if (p == m.rows()) {
                continue;
            }
            Rational[] tmp = g[p];
            g[p] = g[r];
            g[r] = tmp;
            Rational inv = g[r][c].inverse();
            for (int j = 0; j < m.cols(); j++) {
                g[r][j] = g[r][j].mul(inv);
            }
            for (int i = 0; i < m.rows(); i++) {
                if (i != r && !g[i][c].isZero()) {
                    Rational f = g[i][c];
                    for (int j = 0; j < m.cols(); j++) {
                        g[i][j] = g[i][j].sub(f.mul(g[r][j]));
                    }
                }
            }
            pivots.add(c);
            r++;
        }
        return new Rref(Matrix.ofRational(g), List.copyOf(pivots));
    }

    public static int rank(Matrix m) {
        return rref(m).pivots().size();
    }

    /** Determinant by forward elimination with row swaps (sign tracked). */
    public static Rational determinant(Matrix m) {
        m.requireSquare();
        int n = m.rows();
        Rational[][] g = m.copyGrid();
        Rational det = Rational.ONE;
        for (int c = 0; c < n; c++) {
            int p = c;
            while (p < n && g[p][c].isZero()) {
                p++;
            }
            if (p == n) {
                return Rational.ZERO;
            }
            if (p != c) {
                Rational[] tmp = g[p];
                g[p] = g[c];
                g[c] = tmp;
                det = det.negate();
            }
            det = det.mul(g[c][c]);
            for (int i = c + 1; i < n; i++) {
                Rational f = g[i][c].div(g[c][c]);
                for (int j = c; j < n; j++) {
                    g[i][j] = g[i][j].sub(f.mul(g[c][j]));
                }
            }
        }
        return det;
    }

    public static Matrix inverse(Matrix m) {
        m.requireSquare();
        int n = m.rows();
        Rational[][] aug = new Rational[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                aug[i][j] = m.get(i, j);
                aug[i][n + j] = i == j ? Rational.ONE : Rational.ZERO;
            }
        }
        Rref r = rref(Matrix.ofRational(aug));
        if (r.pivots().size() < n || r.pivots().get(n - 1) >= n) {
            throw new SingularMatrixException("matrix is singular");
        }
        Rational[][] out = new Rational[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                out[i][j] = r.matrix().get(i, n + j);
            }
        }
        return Matrix.ofRational(out);
    }

    /** One basis vector per free column, in free-column order, 1 in its own free column. */
    public static List<Rational[]> nullspace(Matrix m) {
        Rref r = rref(m);
        List<Rational[]> basis = new ArrayList<>();
        for (int f = 0; f < m.cols(); f++) {
            if (r.pivots().contains(f)) {
                continue;
            }
            Rational[] x = zeros(m.cols());
            x[f] = Rational.ONE;
            for (int row = 0; row < r.pivots().size(); row++) {
                x[r.pivots().get(row)] = r.matrix().get(row, f).negate();
            }
            basis.add(x);
        }
        return basis;
    }

    /** The original columns sitting at the pivot positions. */
    public static List<Rational[]> columnSpaceBasis(Matrix m) {
        List<Rational[]> cols = new ArrayList<>();
        for (int p : rref(m).pivots()) {
            cols.add(m.col(p));
        }
        return cols;
    }

    public static Solution solve(Matrix a, Rational[] b) {
        if (b.length != a.rows()) {
            throw new IllegalArgumentException("rhs length " + b.length + " != rows " + a.rows());
        }
        int n = a.cols();
        Rational[][] aug = new Rational[a.rows()][n + 1];
        for (int i = 0; i < a.rows(); i++) {
            for (int j = 0; j < n; j++) {
                aug[i][j] = a.get(i, j);
            }
            aug[i][n] = b[i];
        }
        Rref r = rref(Matrix.ofRational(aug));
        if (r.pivots().contains(n)) {
            return new Solution.Inconsistent();
        }
        Rational[] particular = zeros(n);
        for (int row = 0; row < r.pivots().size(); row++) {
            particular[r.pivots().get(row)] = r.matrix().get(row, n);
        }
        if (r.pivots().size() == n) {
            return new Solution.Unique(particular);
        }
        return new Solution.Infinite(particular, nullspace(a));
    }

    static Rational[] zeros(int n) {
        Rational[] z = new Rational[n];
        java.util.Arrays.fill(z, Rational.ZERO);
        return z;
    }
}
