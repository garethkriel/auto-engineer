package ae.bitemporal;

/**
 * A value that holds on the half-open valid-time interval {@code [from, to)}.
 *
 * @param from  first valid instant (inclusive)
 * @param to    first instant after the interval (exclusive); {@link #FOREVER} means open ended
 * @param value the value, never null
 * @param <V>   value type
 */
public record Segment<V>(long from, long to, V value) {

    /** Upper bound meaning "no end". */
    public static final long FOREVER = Long.MAX_VALUE;

    public Segment {
        if (from >= to) {
            throw new IllegalArgumentException("from must be < to: [" + from + ", " + to + ")");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }
}
