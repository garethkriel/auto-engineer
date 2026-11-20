package ae.lp;

/** Exact Gauss-Jordan for small square systems. */
final class LinearSolve {
    private LinearSolve() { }

    /** Solves {@code a x = b}; returns null when {@code a} is singular. Inputs are not modified. */
    static Rational[] solveSquare(Rational[][] a, Rational[] b) {
        int n = b.length;
        Rational[][] t = new Rational[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, t[i], 0, n);
            t[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = -1;
            for (int r = col; r < n && piv < 0; r++) {
                if (!t[r][col].isZero()) {
                    piv = r;
                }
            }
            if (piv < 0) {
                return null;
            }
            Rational[] tmp = t[piv];
            t[piv] = t[col];
            t[col] = tmp;
            Rational inv = Rational.ONE.div(t[col][col]);
            for (int c = col; c <= n; c++) {
                t[col][c] = t[col][c].mul(inv);
            }
            for (int r = 0; r < n; r++) {
                if (r != col && !t[r][col].isZero()) {
                    Rational f = t[r][col];
                    for (int c = col; c <= n; c++) {
                        t[r][c] = t[r][c].sub(f.mul(t[col][c]));
                    }
                }
            }
        }
        Rational[] x = new Rational[n];
        for (int i = 0; i < n; i++) {
            x[i] = t[i][n];
        }
        return x;
    }
}
