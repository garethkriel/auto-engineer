package ae.linalg;

import java.util.Arrays;

/** Immutable rows x cols matrix of {@link Rational}. */
public final class Matrix {
    private final Rational[][] a;
    private final int rows;
    private final int cols;

    private Matrix(Rational[][] a, int rows, int cols) {
        this.a = a;
        this.rows = rows;
        this.cols = cols;
    }

    public static Matrix of(long[][] raw) {
        if (raw.length == 0) {
            throw new IllegalArgumentException("no rows");
        }
        Rational[][] g = new Rational[raw.length][];
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].length != raw[0].length) {
                throw new IllegalArgumentException("ragged rows");
            }
            g[i] = new Rational[raw[i].length];
            for (int j = 0; j < raw[i].length; j++) {
                g[i][j] = Rational.of(raw[i][j]);
            }
        }
        return ofRational(g);
    }

    /** Copies the grid; rejects empty and ragged input. */
    public static Matrix ofRational(Rational[][] raw) {
        if (raw.length == 0 || raw[0].length == 0) {
            throw new IllegalArgumentException("empty matrix");
        }
        Rational[][] g = new Rational[raw.length][];
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].length != raw[0].length) {
                throw new IllegalArgumentException("ragged rows");
            }
            g[i] = raw[i].clone();
            for (Rational r : g[i]) {
                if (r == null) {
                    throw new IllegalArgumentException("null entry");
                }
            }
        }
        return new Matrix(g, g.length, g[0].length);
    }

    public static Matrix zero(int rows, int cols) {
        if (rows < 1 || cols < 1) {
            throw new IllegalArgumentException("dimensions must be >= 1");
        }
        Rational[][] g = new Rational[rows][cols];
        for (Rational[] row : g) {
            Arrays.fill(row, Rational.ZERO);
        }
        return new Matrix(g, rows, cols);
    }

    public static Matrix identity(int n) {
        Matrix m = zero(n, n);
        for (int i = 0; i < n; i++) {
            m.a[i][i] = Rational.ONE;
        }
        return m;
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    public boolean isSquare() {
        return rows == cols;
    }

    public Rational get(int i, int j) {
        if (i < 0 || i >= rows || j < 0 || j >= cols) {
            throw new IndexOutOfBoundsException("(" + i + "," + j + ") in " + rows + "x" + cols);
        }
        return a[i][j];
    }

    public Rational[] row(int i) {
        return a[i].clone();
    }

    public Rational[] col(int j) {
        Rational[] c = new Rational[rows];
        for (int i = 0; i < rows; i++) {
            c[i] = a[i][j];
        }
        return c;
    }

    public Matrix plus(Matrix o) {
        return zip(o, true);
    }

    public Matrix minus(Matrix o) {
        return zip(o, false);
    }

    private Matrix zip(Matrix o, boolean add) {
        if (rows != o.rows || cols != o.cols) {
            throw new IllegalArgumentException("dimension mismatch " + rows + "x" + cols + " vs " + o.rows + "x" + o.cols);
        }
        Rational[][] g = new Rational[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                g[i][j] = add ? a[i][j].add(o.a[i][j]) : a[i][j].sub(o.a[i][j]);
            }
        }
        return new Matrix(g, rows, cols);
    }

    public Matrix times(Rational k) {
        Rational[][] g = new Rational[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                g[i][j] = a[i][j].mul(k);
            }
        }
        return new Matrix(g, rows, cols);
    }

    public Matrix times(Matrix o) {
        if (cols != o.rows) {
            throw new IllegalArgumentException("inner dimensions differ: " + cols + " vs " + o.rows);
        }
        Rational[][] g = new Rational[rows][o.cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < o.cols; j++) {
                Rational s = Rational.ZERO;
                for (int k = 0; k < cols; k++) {
                    s = s.add(a[i][k].mul(o.a[k][j]));
                }
                g[i][j] = s;
            }
        }
        return new Matrix(g, rows, o.cols);
    }

    /** Matrix times column vector. */
    public Rational[] apply(Rational[] x) {
        if (x.length != cols) {
            throw new IllegalArgumentException("vector length " + x.length + " != " + cols);
        }
        Rational[] y = new Rational[rows];
        for (int i = 0; i < rows; i++) {
            Rational s = Rational.ZERO;
            for (int k = 0; k < cols; k++) {
                s = s.add(a[i][k].mul(x[k]));
            }
            y[i] = s;
        }
        return y;
    }

    public Matrix transpose() {
        Rational[][] g = new Rational[cols][rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                g[j][i] = a[i][j];
            }
        }
        return new Matrix(g, cols, rows);
    }

    public Rational trace() {
        requireSquare();
        Rational s = Rational.ZERO;
        for (int i = 0; i < rows; i++) {
            s = s.add(a[i][i]);
        }
        return s;
    }

    /** Square matrix to a non-negative power by repeated squaring. */
    public Matrix pow(int e) {
        requireSquare();
        if (e < 0) {
            throw new IllegalArgumentException("negative exponent");
        }
        Matrix result = identity(rows);
        Matrix base = this;
        int k = e;
        while (k > 0) {
            if ((k & 1) == 1) {
                result = result.times(base);
            }
            base = base.times(base);
            k >>= 1;
        }
        return result;
    }

    void requireSquare() {
        if (!isSquare()) {
            throw new IllegalArgumentException("matrix is " + rows + "x" + cols + ", not square");
        }
    }

    /** Mutable copy of the grid for algorithms in this package. */
    Rational[][] copyGrid() {
        Rational[][] g = new Rational[rows][];
        for (int i = 0; i < rows; i++) {
            g[i] = a[i].clone();
        }
        return g;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Matrix m && rows == m.rows && cols == m.cols && Arrays.deepEquals(a, m.a);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(a);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            for (int j = 0; j < cols; j++) {
                if (j > 0) {
                    sb.append(' ');
                }
                sb.append(a[i][j]);
            }
        }
        return sb.toString();
    }
}
