package ae.spatial;

/** A finite point in the plane. */
public record Point(double x, double y) {
    public Point {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("coordinates must be finite: " + x + ", " + y);
        }
    }
}
