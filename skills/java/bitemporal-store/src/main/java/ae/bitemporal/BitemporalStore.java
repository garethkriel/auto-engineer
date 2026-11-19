package ae.bitemporal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.SortedMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * In-memory bitemporal key/value store. Valid time says when a fact is true in the world; transaction time
 * (ids 1, 2, ...) says when the store learned it. Writes never destroy older knowledge.
 *
 * <p>Thread-safe: every public method is synchronized on the store.
 *
 * @param <K> key type
 * @param <V> value type (non-null, compared with {@code equals})
 */
public final class BitemporalStore<K extends Comparable<K>, V> {

    /** The timeline of one key as published by one transaction. */
    private record Entry<V>(long tx, Timeline<V> timeline) { }

    private final LongSupplier clock;
    private final Map<K, List<Entry<V>>> entries = new HashMap<>();
    private final List<Long> stamps = new ArrayList<>();

    /**
     * @param clock source of transaction timestamps; must never decrease between recorded transactions
     */
    public BitemporalStore(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Id of the latest transaction; 0 when nothing was ever recorded. */
    public synchronized long currentTx() {
        return stamps.size();
    }

    /** Convenience single-operation transaction. */
    public long assertValue(K key, long from, long to, V value) {
        return transact(t -> t.assertValue(key, from, to, value));
    }

    /** Convenience single-operation transaction. */
    public long retract(K key, long from, long to) {
        return transact(t -> t.retract(key, from, to));
    }

    /**
     * Runs {@code work} as one atomic transaction. Returns the new transaction id, or the current id when the
     * transaction changed nothing (nothing is recorded then). If {@code work} throws, or the clock went
     * backwards, nothing is recorded.
     */
    public synchronized long transact(Consumer<Tx<K, V>> work) {
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }
        Tx<K, V> tx = new Tx<>(this);
        try {
            work.accept(tx);
        } finally {
            tx.close();
        }
        Map<K, Timeline<V>> changed = new HashMap<>();
        for (Map.Entry<K, Timeline<V>> e : tx.workingCopies().entrySet()) {
            if (!e.getValue().equals(latest(e.getKey()))) {
                changed.put(e.getKey(), e.getValue());
            }
        }
        if (changed.isEmpty()) {
            return currentTx();
        }
        long ts = clock.getAsLong();
        if (!stamps.isEmpty() && ts < stamps.get(stamps.size() - 1)) {
            throw new IllegalStateException("clock went backwards: " + ts);
        }
        long id = stamps.size() + 1L;
        stamps.add(ts);
        for (Map.Entry<K, Timeline<V>> e : changed.entrySet()) {
            entries.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new Entry<>(id, e.getValue()));
        }
        return id;
    }

    /** Latest transaction whose timestamp is {@code <= timestamp}; 0 when there is none. */
    public synchronized long txAtTime(long timestamp) {
        int lo = 0;
        int hi = stamps.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (stamps.get(mid) <= timestamp) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Value of {@code key} at {@code validTime} as known after transaction {@code tx}. */
    public synchronized Optional<V> valueAt(K key, long validTime, long tx) {
        return timelineAt(key, tx).valueAt(validTime);
    }

    /** Latest knowledge. */
    public synchronized Optional<V> valueAt(K key, long validTime) {
        return valueAt(key, validTime, currentTx());
    }

    /** Segments of {@code key} as known after transaction {@code tx}. */
    public synchronized List<Segment<V>> history(K key, long tx) {
        return timelineAt(key, tx).segments();
    }

    /** Latest knowledge. */
    public synchronized List<Segment<V>> history(K key) {
        return history(key, currentTx());
    }

    /** Every key that has a value at {@code validTime} as known after {@code tx}, sorted by key. */
    public synchronized SortedMap<K, V> snapshot(long validTime, long tx) {
        checkTx(tx);
        TreeMap<K, V> out = new TreeMap<>();
        for (K key : entries.keySet()) {
            timelineAt(key, tx).valueAt(validTime).ifPresent(v -> out.put(key, v));
        }
        return Collections.unmodifiableSortedMap(out);
    }

    /** Valid-time pieces where the knowledge after {@code txA} differs from the knowledge after {@code txB}. */
    public synchronized List<Delta<V>> diff(K key, long txA, long txB) {
        List<Segment<V>> a = timelineAt(key, txA).segments();
        List<Segment<V>> b = timelineAt(key, txB).segments();
        TreeSet<Long> cuts = new TreeSet<>();
        for (Segment<V> s : a) {
            cuts.add(s.from());
            cuts.add(s.to());
        }
        for (Segment<V> s : b) {
            cuts.add(s.from());
            cuts.add(s.to());
        }
        Timeline<V> ta = timelineAt(key, txA);
        Timeline<V> tb = timelineAt(key, txB);
        List<Delta<V>> out = new ArrayList<>();
        Long prev = null;
        for (long cut : cuts) {
            if (prev != null) {
                V before = ta.valueAt(prev).orElse(null);
                V after = tb.valueAt(prev).orElse(null);
                if (!Objects.equals(before, after)) {
                    int last = out.size() - 1;
                    Delta<V> d = last >= 0 ? out.get(last) : null;
                    if (d != null && d.to() == prev && Objects.equals(d.before(), before)
                            && Objects.equals(d.after(), after)) {
                        out.set(last, new Delta<>(d.from(), cut, before, after));
                    } else {
                        out.add(new Delta<>(prev, cut, before, after));
                    }
                }
            }
            prev = cut;
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * How the belief about one valid instant changed over transaction time. Consecutive equal beliefs are
     * not repeated; an instant that only later became known starts at the first transaction that valued it.
     */
    public synchronized List<Revision<V>> revisions(K key, long validTime) {
        List<Revision<V>> out = new ArrayList<>();
        V last = null;
        for (Entry<V> e : entries.getOrDefault(key, List.of())) {
            V now = e.timeline().valueAt(validTime).orElse(null);
            if (!Objects.equals(now, last)) {
                out.add(new Revision<>(e.tx(), stamps.get((int) e.tx() - 1), now));
                last = now;
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ---- internals (callers hold the lock) ----

    Timeline<V> latest(K key) {
        List<Entry<V>> list = entries.get(key);
        return list == null ? Timeline.empty() : list.get(list.size() - 1).timeline();
    }

    private Timeline<V> timelineAt(K key, long tx) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        checkTx(tx);
        List<Entry<V>> list = entries.get(key);
        if (list == null) {
            return Timeline.empty();
        }
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).tx() <= tx) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo == 0 ? Timeline.empty() : list.get(lo - 1).timeline();
    }

    private void checkTx(long tx) {
        if (tx < 0 || tx > stamps.size()) {
            throw new IllegalArgumentException("unknown transaction " + tx + " (current " + stamps.size() + ")");
        }
    }
}
