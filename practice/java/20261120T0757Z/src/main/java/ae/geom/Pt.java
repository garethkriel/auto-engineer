package ae.geom;

import java.util.Comparator;

/** A lattice point. Coordinates are limited to +-1e9 so that cross products and squared distances fit in a long. */
public record Pt(long x, long y) {
    /** Largest allowed absolute coordinate. */
    public static final long LIMIT = 1_000_000_000L;

    /** Lexicographic order: x first, then y. */
    public static final Comparator<Pt> ORDER = Comparator.comparingLong(Pt::x).thenComparingLong(Pt::y);

    public Pt {
        if (Math.abs(x) > LIMIT || Math.abs(y) > LIMIT) {
            throw new IllegalArgumentException("coordinate out of range: " + x + "," + y);
        }
    }
}
