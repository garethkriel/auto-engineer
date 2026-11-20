package ae.erasure;

import java.util.Arrays;

/** A small matrix over GF(256). Defensive copies on the way in and out. */
public final class GfMatrix {
    private final byte[][] cells;
    private final int cols;

    public GfMatrix(byte[][] rows) {
        if (rows.length == 0) {
            throw new IllegalArgumentException("empty matrix");
        }
        this.cols = rows[0].length;
        this.cells = new byte[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            if (rows[i].length != cols) {
                throw new IllegalArgumentException("ragged matrix");
            }
            cells[i] = rows[i].clone();
        }
    }

    public static GfMatrix identity(int n) {
        byte[][] a = new byte[n][n];
        for (int i = 0; i < n; i++) {
            a[i][i] = 1;
        }
        return new GfMatrix(a);
    }

    public int rows() {
        return cells.length;
    }

    public int cols() {
        return cols;
    }

    public int get(int r, int c) {
        return cells[r][c] & 0xFF;
    }

    public byte[] row(int r) {
        return cells[r].clone();
    }

    public GfMatrix multiply(GfMatrix o) {
        if (cols != o.rows()) {
            throw new IllegalArgumentException("dimension mismatch " + cols + " vs " + o.rows());
        }
        byte[][] out = new byte[rows()][o.cols];
        for (int i = 0; i < rows(); i++) {
            for (int j = 0; j < o.cols; j++) {
                int acc = 0;
                for (int t = 0; t < cols; t++) {
                    acc ^= Gf256.mul(cells[i][t], o.cells[t][j]);
                }
                out[i][j] = (byte) acc;
            }
        }
        return new GfMatrix(out);
    }

    public GfMatrix subMatrix(int[] rowIndexes) {
        byte[][] out = new byte[rowIndexes.length][];
        for (int i = 0; i < rowIndexes.length; i++) {
            out[i] = cells[rowIndexes[i]];
        }
        return new GfMatrix(out);
    }

    /** Gauss-Jordan inverse of a square matrix; {@link ArithmeticException} if singular. */
    public GfMatrix invert() {
        int n = rows();
        if (n != cols) {
            throw new IllegalArgumentException("not square");
        }
        byte[][] a = new byte[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(cells[i], 0, a[i], 0, n);
            a[i][n + i] = 1;
        }
        for (int c = 0; c < n; c++) {
            int p = c;
            while (p < n && a[p][c] == 0) {
                p++;
            }
            if (p == n) {
                throw new ArithmeticException("singular matrix");
            }
            byte[] tmp = a[c];
            a[c] = a[p];
            a[p] = tmp;
            int scale = Gf256.inv(a[c][c]);
            for (int j = 0; j < 2 * n; j++) {
                a[c][j] = (byte) Gf256.mul(a[c][j], scale);
            }
            for (int r = 0; r < n; r++) {
                int f = a[r][c] & 0xFF;
                if (r != c && f != 0) {
                    for (int j = 0; j < 2 * n; j++) {
                        a[r][j] ^= (byte) Gf256.mul(a[c][j], f);
                    }
                }
            }
        }
        byte[][] out = new byte[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], n, out[i], 0, n);
        }
        return new GfMatrix(out);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GfMatrix g && Arrays.deepEquals(cells, g.cells);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(cells);
    }

    @Override
    public String toString() {
        return Arrays.deepToString(cells);
    }
}
