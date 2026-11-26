package ae.linalg;

import java.math.BigInteger;

/** Fraction-free integer determinant (Bareiss), independent of the rational code path. */
public final class Bareiss {
    private Bareiss() { }

    public static BigInteger determinant(long[][] a) {
        int n = a.length;
        if (n == 0) {
            throw new IllegalArgumentException("empty");
        }
        BigInteger[][] g = new BigInteger[n][n];
        for (int i = 0; i < n; i++) {
            if (a[i].length != n) {
                throw new IllegalArgumentException("not square");
            }
            for (int j = 0; j < n; j++) {
                g[i][j] = BigInteger.valueOf(a[i][j]);
            }
        }
        BigInteger prev = BigInteger.ONE;
        int sign = 1;
        for (int k = 0; k < n - 1; k++) {
            if (g[k][k].signum() == 0) {
                int s = k + 1;
                while (s < n && g[s][k].signum() == 0) {
                    s++;
                }
                if (s == n) {
                    return BigInteger.ZERO;
                }
                BigInteger[] tmp = g[s];
                g[s] = g[k];
                g[k] = tmp;
                sign = -sign;
            }
            for (int i = k + 1; i < n; i++) {
                for (int j = k + 1; j < n; j++) {
                    g[i][j] = g[i][j].multiply(g[k][k]).subtract(g[i][k].multiply(g[k][j])).divide(prev);
                }
            }
            prev = g[k][k];
        }
        BigInteger det = g[n - 1][n - 1];
        return sign < 0 ? det.negate() : det;
    }
}
