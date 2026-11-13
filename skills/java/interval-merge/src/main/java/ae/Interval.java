package ae;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable closed integer interval {@code [start, end]}. */
public final class Interval {
    private static final Pattern FORMAT = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]");

    private final int start;
    private final int end;

    /** Creates {@code [start, end]}; throws if {@code start > end}. */
    public Interval(int start, int end) {
        if (start > end) {
            throw new IllegalArgumentException("start " + start + " > end " + end);
        }
        this.start = start;
        this.end = end;
    }

    /** Parses text such as {@code "[3,7]"}; surrounding whitespace is ignored. */
    public static Interval parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher m = FORMAT.matcher(text.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("bad interval: " + text);
        }
        try {
            return new Interval(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad interval: " + text, e);
        }
    }

    public int start() { return start; }

    public int end() { return end; }

    /** Number of integers contained, computed in long arithmetic. */
    public long length() {
        return (long) end - start + 1;
    }

    public boolean contains(int value) {
        return value >= start && value <= end;
    }

    public boolean overlaps(Interval o) {
        return start <= o.end && o.start <= end;
    }

    public Optional<Interval> intersection(Interval o) {
        if (!overlaps(o)) {
            return Optional.empty();
        }
        return Optional.of(new Interval(Math.max(start, o.start), Math.min(end, o.end)));
    }

    /** Union if the intervals overlap or are adjacent, else empty. */
    public Optional<Interval> merge(Interval o) {
        boolean joinable = (long) start <= (long) o.end + 1 && (long) o.start <= (long) end + 1;
        if (!joinable) {
            return Optional.empty();
        }
        return Optional.of(new Interval(Math.min(start, o.start), Math.max(end, o.end)));
    }

    /** Sorted, minimal list of disjoint, non-adjacent intervals covering the input. */
    public static List<Interval> mergeAll(List<Interval> intervals) {
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingInt(Interval::start));
        List<Interval> out = new ArrayList<>();
        for (Interval next : sorted) {
            if (!out.isEmpty()) {
                Optional<Interval> m = out.get(out.size() - 1).merge(next);
                if (m.isPresent()) {
                    out.set(out.size() - 1, m.get());
                    continue;
                }
            }
            out.add(next);
        }
        return out;
    }

    @Override public boolean equals(Object o) {
        return o instanceof Interval i && i.start == start && i.end == end;
    }

    @Override public int hashCode() {
        return Objects.hash(start, end);
    }

    @Override public String toString() {
        return "[" + start + "," + end + "]";
    }
}
