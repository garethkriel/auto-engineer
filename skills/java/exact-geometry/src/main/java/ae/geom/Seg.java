package ae.geom;

import java.util.Objects;

/** A closed line segment; a zero-length segment is a single point. */
public record Seg(Pt a, Pt b) {
    public Seg {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
    }
}
