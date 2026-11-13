package ae;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Utilities for closed integer intervals. */
public final class Intervals {
    private Intervals() { }

    /** A closed integer interval [start, end]. */
    public record Interval(int start, int end) {
        public Interval {
            if (start > end) {
                throw new IllegalArgumentException("start > end: " + start + " > " + end);
            }
        }
    }

    /** Sorts and merges overlapping or adjacent intervals; the input is not modified. */
    public static List<Interval> merge(List<Interval> intervals) {
        Objects.requireNonNull(intervals, "intervals");
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingInt(Interval::start).thenComparingInt(Interval::end));
        List<Interval> out = new ArrayList<>();
        for (Interval next : sorted) {
            if (out.isEmpty()) {
                out.add(next);
                continue;
            }
            Interval last = out.get(out.size() - 1);
            // long arithmetic avoids overflow of end + 1 at Integer.MAX_VALUE
            if ((long) last.end() + 1 >= next.start()) {
                out.set(out.size() - 1, new Interval(last.start(), Math.max(last.end(), next.end())));
            } else {
                out.add(next);
            }
        }
        return out;
    }

    /** Number of integer points covered by the union of the intervals. */
    public static long coveredLength(List<Interval> intervals) {
        long total = 0;
        for (Interval iv : merge(intervals)) {
            total += (long) iv.end() - iv.start() + 1;
        }
        return total;
    }

    /** Sub-intervals of {@code window} not covered by any interval. */
    public static List<Interval> gaps(List<Interval> intervals, Interval window) {
        Objects.requireNonNull(window, "window");
        List<Interval> out = new ArrayList<>();
        long cursor = window.start(); // next uncovered candidate point
        for (Interval iv : merge(intervals)) {
            if (iv.end() < window.start()) {
                continue;
            }
            if (iv.start() > window.end()) {
                break;
            }
            if (iv.start() > cursor) {
                out.add(new Interval((int) cursor, iv.start() - 1));
            }
            cursor = Math.max(cursor, (long) iv.end() + 1);
        }
        if (cursor <= window.end()) {
            out.add(new Interval((int) cursor, window.end()));
        }
        return out;
    }
}
