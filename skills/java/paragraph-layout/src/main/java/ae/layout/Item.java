package ae.layout;

/** A unit of a paragraph in Knuth-Plass terms: box, glue or penalty. */
public sealed interface Item permits Item.Box, Item.Glue, Item.Penalty {
    /** Penalty value at or above which a break is forbidden (and at or below the negation, forced). */
    int PENALTY_INF = 10_000;

    /** Unbreakable content of the given width; {@code text} is kept for rendering. */
    record Box(int width, String text) implements Item {
        public Box {
            if (width < 0) {
                throw new IllegalArgumentException("box width must not be negative: " + width);
            }
            if (text == null) {
                throw new IllegalArgumentException("box text must not be null");
            }
        }
    }

    /** Breakable space. A break is legal only right after a box. */
    record Glue(int width, int stretch, int shrink) implements Item {
        public Glue {
            if (width < 0 || stretch < 0 || shrink < 0) {
                throw new IllegalArgumentException("glue values must not be negative");
            }
            if (shrink > width) {
                throw new IllegalArgumentException("glue shrink " + shrink + " exceeds width " + width);
            }
        }
    }

    /** Optional break with a cost. Its width counts only if the break is taken (a hyphen). */
    record Penalty(int width, int penalty, boolean flagged) implements Item {
        public Penalty {
            if (width < 0) {
                throw new IllegalArgumentException("penalty width must not be negative: " + width);
            }
        }
    }
}
