package ae.bitemporal;

/**
 * A piece of valid time on which two knowledge states disagree.
 *
 * @param from   first valid instant (inclusive)
 * @param to     end (exclusive)
 * @param before value in the earlier state, or null when there was none
 * @param after  value in the later state, or null when there is none
 * @param <V>    value type
 */
public record Delta<V>(long from, long to, V before, V after) {

    public Delta {
        if (from >= to) {
            throw new IllegalArgumentException("from must be < to: [" + from + ", " + to + ")");
        }
    }
}
