package bench.vclock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A replica of a key/value store that keeps concurrent versions using vector clocks. */
public final class Replica {
    private final String id;
    private final Map<String, List<Version>> store = new LinkedHashMap<>();

    /** One version of a key: a clock plus a value (null value means tombstone). */
    private record Version(Map<String, Long> clock, String value, boolean tombstone) { }

    /**
     * Creates a replica.
     *
     * @param id non-empty replica id
     */
    public Replica(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must be non-empty");
        }
        this.id = id;
    }

    /** Returns the replica id. */
    public String id() {
        return id;
    }

    /** Result of a read: the live values and the merged context. */
    public record Read(List<String> values, Context context) { }

    /** Immutable vector clock. */
    public static final class Context {
        /** The empty clock. */
        public static final Context EMPTY = new Context(Map.of());

        private final Map<String, Long> clock;

        private Context(Map<String, Long> clock) {
            this.clock = Map.copyOf(clock);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Context c && c.clock.equals(clock);
        }

        @Override
        public int hashCode() {
            return clock.hashCode();
        }

        @Override
        public String toString() {
            return "Context" + new java.util.TreeMap<>(clock);
        }
    }

    /** Reads a key. */
    public Read get(String key) {
        requireNonNull(key);
        List<Version> versions = store.getOrDefault(key, List.of());
        List<String> values = new ArrayList<>();
        Map<String, Long> max = new HashMap<>();
        for (Version v : versions) {
            if (!v.tombstone()) {
                values.add(v.value());
            }
            for (Map.Entry<String, Long> e : v.clock().entrySet()) {
                max.merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        Collections.sort(values);
        return new Read(Collections.unmodifiableList(values), new Context(max));
    }

    /** Writes a value, superseding everything covered by the context. */
    public void put(String key, String value, Context context) {
        requireNonNull(key);
        requireNonNull(value);
        requireNonNull(context);
        write(key, value, false, context);
    }

    /** Deletes a key by writing a tombstone. */
    public void delete(String key, Context context) {
        requireNonNull(key);
        requireNonNull(context);
        write(key, null, true, context);
    }

    private void write(String key, String value, boolean tombstone, Context context) {
        List<Version> current = store.computeIfAbsent(key, k -> new ArrayList<>());
        Map<String, Long> c = new HashMap<>(context.clock);
        long base = context.clock.getOrDefault(id, 0L);
        for (Version v : current) {
            base = Math.max(base, v.clock().getOrDefault(id, 0L));
        }
        c.put(id, base + 1);
        current.removeIf(v -> leq(v.clock(), c));
        current.add(new Version(Map.copyOf(c), value, tombstone));
    }

    /** Merges the other replica's versions into this one. */
    public void syncFrom(Replica other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null");
        }
        if (other == this) {
            return;
        }
        for (Map.Entry<String, List<Version>> e : other.store.entrySet()) {
            List<Version> union = new ArrayList<>(store.getOrDefault(e.getKey(), List.of()));
            for (Version v : e.getValue()) {
                boolean present = false;
                for (Version u : union) {
                    if (u.clock().equals(v.clock())) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    union.add(v);
                }
            }
            List<Version> kept = new ArrayList<>();
            for (Version v : union) {
                boolean dominated = false;
                for (Version u : union) {
                    if (u != v && leq(v.clock(), u.clock()) && !v.clock().equals(u.clock())) {
                        dominated = true;
                        break;
                    }
                }
                if (!dominated) {
                    kept.add(v);
                }
            }
            store.put(e.getKey(), kept);
        }
    }

    private static boolean leq(Map<String, Long> a, Map<String, Long> b) {
        for (Map.Entry<String, Long> e : a.entrySet()) {
            if (e.getValue() > b.getOrDefault(e.getKey(), 0L)) {
                return false;
            }
        }
        return true;
    }

    private static void requireNonNull(Object o) {
        if (o == null) {
            throw new IllegalArgumentException("argument must not be null");
        }
    }
}
