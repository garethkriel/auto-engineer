package ae.mvcc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * In-memory multi-version key-value store with snapshot or serializable transactions.
 * Thread safe: all shared state is guarded by {@code this}.
 */
public final class MvccStore<K extends Comparable<? super K>, V> {

    /** One committed version; {@code tombstone} marks a delete. */
    record Version<V>(long commitTs, V value, boolean tombstone) { }

    private final TreeMap<K, List<Version<V>>> data = new TreeMap<>();
    /** Active transactions' start timestamps with their multiplicity. */
    private final TreeMap<Long, Integer> active = new TreeMap<>();
    private long clock;

    /** Starts a transaction reading the latest committed state. */
    public synchronized Transaction<K, V> begin(Isolation isolation) {
        Objects.requireNonNull(isolation, "isolation");
        active.merge(clock, 1, Integer::sum);
        return new Transaction<>(this, isolation, clock);
    }

    public Transaction<K, V> begin() {
        return begin(Isolation.SNAPSHOT);
    }

    /** Timestamp of the last commit (0 when nothing was committed). */
    public synchronized long lastCommitTs() {
        return clock;
    }

    /** Number of transactions that began and neither committed nor rolled back. */
    public synchronized int activeTransactions() {
        int n = 0;
        for (int c : active.values()) {
            n += c;
        }
        return n;
    }

    /** Total stored versions including tombstones (for GC tests). */
    public synchronized int versionCount() {
        int n = 0;
        for (List<Version<V>> vs : data.values()) {
            n += vs.size();
        }
        return n;
    }

    /** Number of keys that have any stored version. */
    public synchronized int keyCount() {
        return data.size();
    }

    /**
     * Drops versions no active transaction can see.
     *
     * @return the number of versions removed
     */
    public synchronized int vacuum() {
        long watermark = active.isEmpty() ? clock : active.firstKey();
        int removed = 0;
        Iterator<Map.Entry<K, List<Version<V>>>> it = data.entrySet().iterator();
        while (it.hasNext()) {
            List<Version<V>> vs = it.next().getValue();
            int keep = indexVisible(vs, watermark);
            if (keep < 0) {
                continue; // every version is newer than the watermark
            }
            // vs[keep] is what the oldest reader sees; older ones are dead.
            int drop = keep;
            if (vs.get(keep).tombstone()) {
                drop = keep + 1; // nothing older remains, so the tombstone itself is dead
            }
            if (drop > 0) {
                vs.subList(0, drop).clear();
                removed += drop;
            }
            if (vs.isEmpty()) {
                it.remove();
            }
        }
        return removed;
    }

    // ---- package-private operations used by Transaction ----

    /** Index of the newest version with commitTs &lt;= ts, or -1. */
    private static <V> int indexVisible(List<Version<V>> vs, long ts) {
        int lo = 0;
        int hi = vs.size() - 1;
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (vs.get(mid).commitTs() <= ts) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /** Snapshot read: the visible version or null when absent or deleted. */
    synchronized Version<V> readVisible(K key, long ts) {
        List<Version<V>> vs = data.get(key);
        if (vs == null) {
            return null;
        }
        int i = indexVisible(vs, ts);
        return i < 0 ? null : vs.get(i);
    }

    /** Snapshot scan of live entries in [from, to). */
    synchronized TreeMap<K, V> readRange(K from, K to, long ts) {
        TreeMap<K, V> out = new TreeMap<>();
        if (from.compareTo(to) >= 0) {
            return out;
        }
        for (Map.Entry<K, List<Version<V>>> e : data.subMap(from, true, to, false).entrySet()) {
            int i = indexVisible(e.getValue(), ts);
            if (i >= 0 && !e.getValue().get(i).tombstone()) {
                out.put(e.getKey(), e.getValue().get(i).value());
            }
        }
        return out;
    }

    private boolean changedAfter(K key, long ts) {
        List<Version<V>> vs = data.get(key);
        return vs != null && vs.get(vs.size() - 1).commitTs() > ts;
    }

    /**
     * Validates and applies a commit atomically.
     *
     * @param writes buffered writes; a null value means delete
     * @return the commit timestamp (the start timestamp for a read-only transaction)
     */
    synchronized long commit(Transaction<K, V> tx, Map<K, V> writes, java.util.Set<K> reads,
                             List<Object[]> ranges) {
        try {
            if (writes.isEmpty()) {
                return tx.startTs(); // read-only: serialises at its snapshot point
            }
            for (K k : writes.keySet()) {
                if (changedAfter(k, tx.startTs())) {
                    throw new ConflictException("write-write conflict on key " + k);
                }
            }
            if (tx.isolation() == Isolation.SERIALIZABLE) {
                for (K k : reads) {
                    if (changedAfter(k, tx.startTs())) {
                        throw new ConflictException("read of key " + k + " is stale");
                    }
                }
                for (Object[] r : ranges) {
                    @SuppressWarnings("unchecked")
                    K from = (K) r[0];
                    @SuppressWarnings("unchecked")
                    K to = (K) r[1];
                    if (from.compareTo(to) >= 0) {
                        continue;
                    }
                    for (K k : data.subMap(from, true, to, false).keySet()) {
                        if (changedAfter(k, tx.startTs())) {
                            throw new ConflictException("range [" + from + ", " + to + ") changed at " + k);
                        }
                    }
                }
            }
            long ts = ++clock;
            for (Map.Entry<K, V> w : writes.entrySet()) {
                V v = w.getValue();
                data.computeIfAbsent(w.getKey(), k -> new ArrayList<>())
                    .add(new Version<>(ts, v, v == null));
            }
            return ts;
        } finally {
            release(tx.startTs());
        }
    }

    synchronized void release(long startTs) {
        Integer c = active.get(startTs);
        if (c == null) {
            return;
        }
        if (c == 1) {
            active.remove(startTs);
        } else {
            active.put(startTs, c - 1);
        }
    }
}
