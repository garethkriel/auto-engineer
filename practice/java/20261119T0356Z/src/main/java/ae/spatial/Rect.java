package ae.spatial;

/** An axis-aligned rectangle, closed on all sides. */
public record Rect(double minX, double minY, double maxX, double maxY) {
    public Rect {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
            throw new IllegalArgumentException("bounds must be finite");
        }
        if (minX > maxX || minY > maxY) {
            throw new IllegalArgumentException("min must not exceed max");
        }
    }

    public boolean contains(Point p) {
        return p.x() >= minX && p.x() <= maxX && p.y() >= minY && p.y() <= maxY;
    }

    public boolean intersects(Rect o) {
        return o.minX <= maxX && o.maxX >= minX && o.minY <= maxY && o.maxY >= minY;
    }

    /** Squared distance from {@code p} to the nearest point of this rectangle; 0 when inside. */
    public double distanceSquared(Point p) {
        return distanceSquared(p.x(), p.y());
    }

    double distanceSquared(double x, double y) {
        double dx = Math.max(Math.max(minX - x, 0), x - maxX);
        double dy = Math.max(Math.max(minY - y, 0), y - maxY);
        return dx * dx + dy * dy;
    }
}
