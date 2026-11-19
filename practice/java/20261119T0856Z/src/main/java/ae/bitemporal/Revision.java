package ae.bitemporal;

/**
 * A change in what the store believed about one valid instant.
 *
 * @param tx        transaction that changed the belief
 * @param timestamp that transaction's timestamp
 * @param value     the new belief, or null when the instant became unknown
 * @param <V>       value type
 */
public record Revision<V>(long tx, long timestamp, V value) { }
