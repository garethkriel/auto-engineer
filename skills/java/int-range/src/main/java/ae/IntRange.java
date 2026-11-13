package ae;

import java.util.Objects;

/** Immutable half-open integer range {@code [start, end)}. */
public final class IntRange {
    private final int start;
    private final int end;

    public IntRange(int start, int end) {
        if (start > end) {
            throw new IllegalArgumentException("start " + start + " > end " + end);
        }
        this.start = start;
        this.end = end;
    }

    public static IntRange of(int start, int end) {
        return new IntRange(start, end);
    }

    public int start() { return start; }

    public int end() { return end; }

    /** Number of integers in the range; long to avoid int overflow. */
    public long length() { return (long) end - start; }

    public boolean isEmpty() { return start == end; }

    public boolean contains(int v) { return v >= start && v < end; }

    public boolean overlaps(IntRange o) {
        Objects.requireNonNull(o);
        if (isEmpty() || o.isEmpty()) {
            return false;
        }
        return start < o.end && o.start < end;
    }

    public IntRange merge(IntRange o) {
        Objects.requireNonNull(o);
        if (o.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return o;
        }
        if (start > o.end || o.start > end) {
            throw new IllegalArgumentException("gap between " + this + " and " + o);
        }
        return new IntRange(Math.min(start, o.start), Math.max(end, o.end));
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof IntRange r && r.start == start && r.end == end;
    }

    @Override
    public int hashCode() { return Objects.hash(start, end); }

    @Override
    public String toString() { return "[" + start + ", " + end + ")"; }
}
