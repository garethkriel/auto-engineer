package ae.mvcc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** A single-threaded unit of work against an {@link MvccStore}. Not thread safe itself. */
public final class Transaction<K extends Comparable<? super K>, V> implements AutoCloseable {
    private final MvccStore<K, V> store;
    private final Isolation isolation;
    private final long startTs;
    /** Buffered writes; a null value is a delete. */
    private final TreeMap<K, V> writes = new TreeMap<>();
    private final Set<K> reads = new HashSet<>();
    private final List<Object[]> ranges = new ArrayList<>();
    private boolean open = true;

    Transaction(MvccStore<K, V> store, Isolation isolation, long startTs) {
        this.store = store;
        this.isolation = isolation;
        this.startTs = startTs;
    }

    long startTs() {
        return startTs;
    }

    Isolation isolation() {
        return isolation;
    }

    public boolean isOpen() {
        return open;
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("transaction is closed");
        }
    }

    /** Reads a key: own writes first, then the snapshot. */
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        reads.add(key);
        if (writes.containsKey(key)) {
            return Optional.ofNullable(writes.get(key));
        }
        MvccStore.Version<V> v = store.readVisible(key, startTs);
        return v == null || v.tombstone() ? Optional.empty() : Optional.of(v.value());
    }

    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        requireOpen();
        writes.put(key, value);
    }

    public void delete(K key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        writes.put(key, null);
    }

    /** Live entries with {@code from <= key < to} in key order, including own writes. */
    public TreeMap<K, V> scan(K from, K to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        requireOpen();
        ranges.add(new Object[] {from, to});
        TreeMap<K, V> out = store.readRange(from, to, startTs);
        if (from.compareTo(to) < 0) {
            for (Map.Entry<K, V> w : writes.subMap(from, true, to, false).entrySet()) {
                if (w.getValue() == null) {
                    out.remove(w.getKey());
                } else {
                    out.put(w.getKey(), w.getValue());
                }
            }
        }
        return out;
    }

    /**
     * Commits the buffered writes.
     *
     * @return the commit timestamp
     * @throws ConflictException if validation fails; the transaction is then closed and nothing applied
     */
    public long commit() {
        requireOpen();
        open = false;
        return store.commit(this, writes, reads, ranges);
    }

    /** Discards the transaction; a no-op when already closed. */
    public void rollback() {
        if (open) {
            open = false;
            store.release(startTs);
        }
    }

    /** Same as {@link #rollback()}, so try-with-resources never leaks an active snapshot. */
    @Override
    public void close() {
        rollback();
    }
}
