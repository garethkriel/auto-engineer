package bench.vclock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A replica of a key-value store using vector clocks and sibling versions. */
public final class Replica {

    /** One version of a key: a clock plus a value, or a tombstone when value is null. */
    private record Version(Context clock, String value) { }

    private final String id;
    private final Map<String, List<Version>> store = new HashMap<>();

    public Replica(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("bad id");
        }
        this.id = id;
    }

    public String id() {
        return id;
    }

    public synchronized Read get(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        List<Version> vs = store.get(key);
        if (vs == null) {
            return new Read(List.of(), Context.EMPTY);
        }
        List<String> values = new ArrayList<>();
        Map<String, Long> max = new HashMap<>();
        for (Version v : vs) {
            if (v.value() != null) {
                values.add(v.value());
            }
            for (Map.Entry<String, Long> e : v.clock().counters.entrySet()) {
                max.merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        Collections.sort(values);
        return new Read(Collections.unmodifiableList(values), new Context(max));
    }

    public synchronized void put(String key, String value, Context context) {
        if (key == null || value == null || context == null) {
            throw new IllegalArgumentException("null argument");
        }
        write(key, value, context);
    }

    public synchronized void delete(String key, Context context) {
        if (key == null || context == null) {
            throw new IllegalArgumentException("null argument");
        }
        write(key, null, context);
    }

    private void write(String key, String value, Context context) {
        List<Version> vs = store.computeIfAbsent(key, k -> new ArrayList<>());
        long m = context.get(id);
        for (Version v : vs) {
            m = Math.max(m, v.clock().get(id));
        }
        Map<String, Long> c = new HashMap<>(context.counters);
        c.put(id, m + 1);
        Context nc = new Context(c);
        vs.removeIf(v -> v.clock().leq(nc));
        vs.add(new Version(nc, value));
    }

    public void syncFrom(Replica other) {
        if (other == null) {
            throw new IllegalArgumentException("other is null");
        }
        if (other == this) {
            return;
        }
        Map<String, List<Version>> snapshot = new HashMap<>();
        synchronized (other) {
            for (Map.Entry<String, List<Version>> e : other.store.entrySet()) {
                snapshot.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        synchronized (this) {
            for (Map.Entry<String, List<Version>> e : snapshot.entrySet()) {
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
                        if (u != v && v.clock().leq(u.clock()) && !v.clock().equals(u.clock())) {
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
    }

    public record Read(List<String> values, Context context) { }

    /** An immutable vector clock. */
    public static final class Context {
        public static final Context EMPTY = new Context(Map.of());

        private final Map<String, Long> counters;

        private Context(Map<String, Long> counters) {
            Map<String, Long> m = new HashMap<>();
            for (Map.Entry<String, Long> e : counters.entrySet()) {
                if (e.getValue() > 0) {
                    m.put(e.getKey(), e.getValue());
                }
            }
            this.counters = Collections.unmodifiableMap(m);
        }

        private long get(String id) {
            return counters.getOrDefault(id, 0L);
        }

        private boolean leq(Context o) {
            for (Map.Entry<String, Long> e : counters.entrySet()) {
                if (e.getValue() > o.get(e.getKey())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Context c && counters.equals(c.counters);
        }

        @Override
        public int hashCode() {
            return counters.hashCode();
        }

        @Override
        public String toString() {
            return "Context" + new java.util.TreeMap<>(counters);
        }
    }
}
