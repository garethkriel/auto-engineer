package bench.vclock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replicated key/value store using vector clocks and tombstones. */
public final class Replica {

    /** Immutable vector clock. */
    public static final class Context {
        public static final Context EMPTY = new Context(Map.of());

        private final Map<String, Long> clock;

        private Context(Map<String, Long> clock) {
            this.clock = Map.copyOf(clock);
        }

        long get(String id) {
            return clock.getOrDefault(id, 0L);
        }

        boolean leq(Context o) {
            for (Map.Entry<String, Long> e : clock.entrySet()) {
                if (e.getValue() > o.get(e.getKey())) return false;
            }
            return true;
        }

        boolean lessThan(Context o) {
            return leq(o) && !equals(o);
        }

        Context max(Context o) {
            Map<String, Long> m = new HashMap<>(clock);
            for (Map.Entry<String, Long> e : o.clock.entrySet()) m.merge(e.getKey(), e.getValue(), Math::max);
            return new Context(m);
        }

        Context with(String id, long counter) {
            Map<String, Long> m = new HashMap<>(clock);
            m.put(id, counter);
            return new Context(m);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Context c && clock.equals(c.clock);
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

    public record Read(List<String> values, Context context) { }

    private record Version(Context clock, String value) { } // value null = tombstone

    private final String id;
    private final Map<String, List<Version>> store = new LinkedHashMap<>();

    public Replica(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id");
        this.id = id;
    }

    public String id() {
        return id;
    }

    public Read get(String key) {
        if (key == null) throw new IllegalArgumentException("key");
        List<String> values = new ArrayList<>();
        Context ctx = Context.EMPTY;
        for (Version v : store.getOrDefault(key, List.of())) {
            if (v.value != null) values.add(v.value);
            ctx = ctx.max(v.clock);
        }
        Collections.sort(values);
        return new Read(Collections.unmodifiableList(values), ctx);
    }

    public void put(String key, String value, Context context) {
        if (key == null || value == null || context == null) throw new IllegalArgumentException("null argument");
        write(key, value, context);
    }

    public void delete(String key, Context context) {
        if (key == null || context == null) throw new IllegalArgumentException("null argument");
        write(key, null, context);
    }

    private void write(String key, String value, Context context) {
        List<Version> current = store.computeIfAbsent(key, k -> new ArrayList<>());
        long m = context.get(id);
        for (Version v : current) m = Math.max(m, v.clock.get(id));
        Context c = context.with(id, m + 1);
        current.removeIf(v -> v.clock.leq(c));
        current.add(new Version(c, value));
    }

    public void syncFrom(Replica other) {
        if (other == null) throw new IllegalArgumentException("other");
        if (other == this) return;
        for (Map.Entry<String, List<Version>> e : other.store.entrySet()) {
            List<Version> union = new ArrayList<>(store.getOrDefault(e.getKey(), List.of()));
            for (Version v : e.getValue()) {
                boolean present = false;
                for (Version u : union) {
                    if (u.clock.equals(v.clock)) {
                        present = true;
                        break;
                    }
                }
                if (!present) union.add(v);
            }
            List<Version> kept = new ArrayList<>();
            for (Version v : union) {
                boolean dominated = false;
                for (Version u : union) {
                    if (v.clock.lessThan(u.clock)) {
                        dominated = true;
                        break;
                    }
                }
                if (!dominated) kept.add(v);
            }
            store.put(e.getKey(), kept);
        }
    }
}
