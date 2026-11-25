package ae.ad;

import java.util.Arrays;

/** Immutable row-major matrix of doubles; rows and cols are both at least 1. */
public final class Mat {
    private final int rows;
    private final int cols;
    private final double[] data;

    private Mat(int rows, int cols, double[] data) {
        this.rows = rows;
        this.cols = cols;
        this.data = data;
    }

    /** Creates a matrix from row-major values; the count must equal rows*cols. */
    public static Mat of(int rows, int cols, double... values) {
        checkDims(rows, cols);
        if (values.length != rows * cols) {
            throw new IllegalArgumentException(
                    "expected " + rows * cols + " values for " + rows + "x" + cols + " but got " + values.length);
        }
        return new Mat(rows, cols, values.clone());
    }

    public static Mat zeros(int rows, int cols) {
        checkDims(rows, cols);
        return new Mat(rows, cols, new double[rows * cols]);
    }

    private static void checkDims(int rows, int cols) {
        if (rows < 1 || cols < 1) {
            throw new IllegalArgumentException("dimensions must be positive: " + rows + "x" + cols);
        }
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    /** Shape as {@code "RxC"}. */
    public String shape() {
        return rows + "x" + cols;
    }

    public double get(int r, int c) {
        return data[r * cols + c];
    }

    public double[] toArray() {
        return data.clone();
    }

    boolean sameShape(Mat o) {
        return rows == o.rows && cols == o.cols;
    }

    private void requireSame(Mat o, String op) {
        if (!sameShape(o)) {
            throw new IllegalArgumentException(op + ": shape mismatch " + shape() + " vs " + o.shape());
        }
    }

    public Mat plus(Mat o) {
        requireSame(o, "plus");
        double[] r = new double[data.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = data[i] + o.data[i];
        }
        return new Mat(rows, cols, r);
    }

    /** Elementwise product. */
    public Mat times(Mat o) {
        requireSame(o, "times");
        double[] r = new double[data.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = data[i] * o.data[i];
        }
        return new Mat(rows, cols, r);
    }

    public Mat scaled(double k) {
        return map(v -> v * k);
    }

    Mat map(java.util.function.DoubleUnaryOperator f) {
        double[] r = new double[data.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = f.applyAsDouble(data[i]);
        }
        return new Mat(rows, cols, r);
    }

    /** Elementwise combination of two same-shaped matrices. */
    Mat zip(Mat o, java.util.function.DoubleBinaryOperator f) {
        requireSame(o, "zip");
        double[] r = new double[data.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = f.applyAsDouble(data[i], o.data[i]);
        }
        return new Mat(rows, cols, r);
    }

    public Mat matmul(Mat o) {
        if (cols != o.rows) {
            throw new IllegalArgumentException("matmul: shape mismatch " + shape() + " vs " + o.shape());
        }
        double[] r = new double[rows * o.cols];
        for (int i = 0; i < rows; i++) {
            for (int k = 0; k < cols; k++) {
                double a = data[i * cols + k];
                for (int j = 0; j < o.cols; j++) {
                    r[i * o.cols + j] += a * o.data[k * o.cols + j];
                }
            }
        }
        return new Mat(rows, o.cols, r);
    }

    public Mat transpose() {
        double[] r = new double[data.length];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                r[j * rows + i] = data[i * cols + j];
            }
        }
        return new Mat(cols, rows, r);
    }

    /** Sum of each column as a 1 x cols matrix. */
    Mat columnSums() {
        double[] r = new double[cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                r[j] += data[i * cols + j];
            }
        }
        return new Mat(1, cols, r);
    }

    double sumAll() {
        double s = 0;
        for (double v : data) {
            s += v;
        }
        return s;
    }

    Mat filled(double v) {
        double[] r = new double[data.length];
        Arrays.fill(r, v);
        return new Mat(rows, cols, r);
    }

    /** True when shapes match and every entry differs by at most {@code eps}. */
    public boolean approx(Mat o, double eps) {
        if (!sameShape(o)) {
            return false;
        }
        for (int i = 0; i < data.length; i++) {
            if (!(Math.abs(data[i] - o.data[i]) <= eps)) { // NaN never matches
                return false;
            }
        }
        return true;
    }

    /** Copy with one entry replaced. */
    Mat with(int r, int c, double v) {
        double[] d = data.clone();
        d[r * cols + c] = v;
        return new Mat(rows, cols, d);
    }

    @Override
    public String toString() {
        return shape() + Arrays.toString(data);
    }
}
