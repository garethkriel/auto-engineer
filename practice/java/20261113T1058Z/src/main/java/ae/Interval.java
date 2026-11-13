package ae;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable closed integer interval {@code [low, high]}. */
public final class Interval implements Comparable<Interval> {
    private static final AtomicLong CREATED = new AtomicLong();

    private final int low;
    private final int high;

    private Interval(int low, int high) {
        this.low = low;
        this.high = high;
        CREATED.incrementAndGet();
    }

    /** Creates an interval; {@code low} must not exceed {@code high}. */
    public static Interval of(int low, int high) {
        if (low > high) {
            throw new IllegalArgumentException("low > high: " + low + " > " + high);
        }
        return new Interval(low, high);
    }

    /** Number of instances ever created. */
    public static long createdCount() {
        return CREATED.get();
    }

    public int low() {
        return low;
    }

    public int high() {
        return high;
    }

    public long length() {
        return (long) high - low;
    }

    public boolean contains(int x) {
        return x >= low && x <= high;
    }

    public boolean overlaps(Interval o) {
        return low <= o.high && o.low <= high;
    }

    private boolean touchesOrOverlaps(Interval o) {
        // long arithmetic avoids overflow at Integer.MAX_VALUE
        return (long) low <= (long) o.high + 1 && (long) o.low <= (long) high + 1;
    }

    /** Smallest interval covering both; they must overlap or be adjacent. */
    public Interval merge(Interval o) {
        Objects.requireNonNull(o);
        if (!touchesOrOverlaps(o)) {
            throw new IllegalArgumentException(this + " and " + o + " are disjoint");
        }
        return new Interval(Math.min(low, o.low), Math.max(high, o.high));
    }

    /** Sorted minimal list of disjoint, non-adjacent intervals covering the input. */
    public static List<Interval> mergeAll(List<Interval> intervals) {
        Objects.requireNonNull(intervals);
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(null);
        List<Interval> out = new ArrayList<>();
        Interval cur = null;
        for (Interval next : sorted) {
            if (cur == null) {
                cur = next;
            } else if (cur.touchesOrOverlaps(next)) {
                cur = cur.merge(next);
            } else {
                out.add(cur);
                cur = next;
            }
        }
        if (cur != null) {
            out.add(cur);
        }
        return out;
    }

    @Override
    public int compareTo(Interval o) {
        int c = Integer.compare(low, o.low);
        return c != 0 ? c : Integer.compare(high, o.high);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Interval i && i.low == low && i.high == high;
    }

    @Override
    public int hashCode() {
        return Objects.hash(low, high);
    }

    @Override
    public String toString() {
        return "[" + low + ", " + high + "]";
    }
}
