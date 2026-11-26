package ae.linalg;

/** PA = LU with "first non-zero pivot" row selection. Works for singular matrices too. */
public record Lu(Matrix p, Matrix l, Matrix u, int sign) {

    public static Lu decompose(Matrix m) {
        m.requireSquare();
        int n = m.rows();
        Rational[][] u = m.copyGrid();
        Rational[][] l = new Rational[n][n];
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
            for (int j = 0; j < n; j++) {
                l[i][j] = Rational.ZERO;
            }
        }
        int sign = 1;
        for (int c = 0; c < n; c++) {
            int p = c;
            while (p < n && u[p][c].isZero()) {
                p++;
            }
            if (p == n) {
                continue; // zero column from row c down: nothing to eliminate
            }
            if (p != c) {
                Rational[] t = u[p];
                u[p] = u[c];
                u[c] = t;
                t = l[p];
                l[p] = l[c];
                l[c] = t;
                int pi = perm[p];
                perm[p] = perm[c];
                perm[c] = pi;
                sign = -sign;
            }
            for (int i = c + 1; i < n; i++) {
                Rational f = u[i][c].div(u[c][c]);
                l[i][c] = f;
                for (int j = c; j < n; j++) {
                    u[i][j] = u[i][j].sub(f.mul(u[c][j]));
                }
            }
        }
        for (int i = 0; i < n; i++) {
            l[i][i] = Rational.ONE;
        }
        Rational[][] pg = new Rational[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                pg[i][j] = perm[i] == j ? Rational.ONE : Rational.ZERO;
            }
        }
        return new Lu(Matrix.ofRational(pg), Matrix.ofRational(l), Matrix.ofRational(u), sign);
    }

    public Rational determinant() {
        Rational d = sign < 0 ? Rational.of(-1) : Rational.ONE;
        for (int i = 0; i < u.rows(); i++) {
            d = d.mul(u.get(i, i));
        }
        return d;
    }

    /** Solve A x = b via forward and back substitution. */
    public Rational[] solve(Rational[] b) {
        int n = u.rows();
        if (b.length != n) {
            throw new IllegalArgumentException("rhs length " + b.length + " != " + n);
        }
        for (int i = 0; i < n; i++) {
            if (u.get(i, i).isZero()) {
                throw new SingularMatrixException("matrix is singular");
            }
        }
        Rational[] pb = p.apply(b);
        Rational[] y = new Rational[n];
        for (int i = 0; i < n; i++) {
            Rational s = pb[i];
            for (int j = 0; j < i; j++) {
                s = s.sub(l.get(i, j).mul(y[j]));
            }
            y[i] = s;
        }
        Rational[] x = new Rational[n];
        for (int i = n - 1; i >= 0; i--) {
            Rational s = y[i];
            for (int j = i + 1; j < n; j++) {
                s = s.sub(u.get(i, j).mul(x[j]));
            }
            x[i] = s.div(u.get(i, i));
        }
        return x;
    }
}
