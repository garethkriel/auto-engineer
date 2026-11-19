package ae.stream;

/** Half-open time interval {@code [start, end)}. Ordered by start, then end. */
public record Window(long start, long end) implements Comparable<Window> {
    public Window {
        if (start >= end) {
            throw new IllegalArgumentException("start must be < end: " + start + ", " + end);
        }
    }

    @Override
    public int compareTo(Window o) {
        int c = Long.compare(start, o.start);
        return c != 0 ? c : Long.compare(end, o.end);
    }
}
