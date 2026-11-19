package ae.bitemporal;

import java.util.HashMap;
import java.util.Map;

/**
 * Handle passed to {@link BitemporalStore#transact}. Operations are applied to private working copies and
 * become visible only if the whole transaction commits. Dead once the transaction function returns.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class Tx<K extends Comparable<K>, V> {

    private final BitemporalStore<K, V> store;
    private final Map<K, Timeline<V>> working = new HashMap<>();
    private boolean open = true;

    Tx(BitemporalStore<K, V> store) {
        this.store = store;
    }

    /** Value {@code value} holds on {@code [from, to)}; overwrites what was there. */
    public void assertValue(K key, long from, long to, V value) {
        requireOpen();
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        working.put(key, current(key).assign(from, to, value));
    }

    /** No value on {@code [from, to)}. */
    public void retract(K key, long from, long to) {
        requireOpen();
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        working.put(key, current(key).clear(from, to));
    }

    private Timeline<V> current(K key) {
        Timeline<V> t = working.get(key);
        return t != null ? t : store.latest(key);
    }

    Map<K, Timeline<V>> workingCopies() {
        return working;
    }

    void close() {
        open = false;
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("transaction is closed");
        }
    }
}
