package bench.vclock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A replica of a key/value store that resolves concurrency with vector clocks. */
public final class Replica {
    private record Version(Map<String, Integer> clock, String value) { }

    private final String id;
    private final Map<String, List<Version>> data = new LinkedHashMap<>();

    public Replica(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("bad id");
        this.id = id;
    }

    public String id() {
        return id;
    }

    public Read get(String key) {
        if (key == null) throw new IllegalArgumentException("key is null");
        List<Version> vs = data.get(key);
        if (vs == null || vs.isEmpty()) return new Read(List.of(), Context.EMPTY);
        List<String> values = new ArrayList<>();
        Map<String, Integer> max = new HashMap<>();
        for (Version v : vs) {
            if (v.value != null) values.add(v.value);
            for (Map.Entry<String, Integer> e : v.clock.entrySet()) max.merge(e.getKey(), e.getValue(), Math::max);
        }
        Collections.sort(values);
        return new Read(Collections.unmodifiableList(values), new Context(max));
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
        List<Version> vs = data.computeIfAbsent(key, k -> new ArrayList<>());
        Map<String, Integer> c = new HashMap<>(context.clock);
        int base = c.getOrDefault(id, 0);
        for (Version v : vs) base = Math.max(base, v.clock.getOrDefault(id, 0));
        c.put(id, base + 1);
        vs.removeIf(v -> leq(v.clock, c));
        vs.add(new Version(c, value));
    }

    public void syncFrom(Replica other) {
        if (other == null) throw new IllegalArgumentException("other is null");
        if (other == this) return;
        for (Map.Entry<String, List<Version>> e : other.data.entrySet()) {
            List<Version> union = new ArrayList<>(data.getOrDefault(e.getKey(), List.of()));
            for (Version ov : e.getValue()) {
                boolean dup = false;
                for (Version v : union) {
                    if (v.clock.equals(ov.clock)) { dup = true; break; }
                }
                if (!dup) union.add(ov);
            }
            List<Version> kept = new ArrayList<>();
            for (Version v : union) {
                boolean dominated = false;
                for (Version w : union) {
                    if (w != v && leq(v.clock, w.clock) && !v.clock.equals(w.clock)) { dominated = true; break; }
                }
                if (!dominated) kept.add(v);
            }
            data.put(e.getKey(), kept);
        }
    }

    private static boolean leq(Map<String, Integer> a, Map<String, Integer> b) {
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            if (e.getValue() > b.getOrDefault(e.getKey(), 0)) return false;
        }
        return true;
    }

    public record Read(List<String> values, Context context) { }

    /** Immutable vector clock. */
    public static final class Context {
        public static final Context EMPTY = new Context(Map.of());

        private final Map<String, Integer> clock;

        private Context(Map<String, Integer> clock) {
            Map<String, Integer> m = new HashMap<>();
            for (Map.Entry<String, Integer> e : clock.entrySet()) {
                if (e.getValue() > 0) m.put(e.getKey(), e.getValue());
            }
            this.clock = Collections.unmodifiableMap(m);
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
}
