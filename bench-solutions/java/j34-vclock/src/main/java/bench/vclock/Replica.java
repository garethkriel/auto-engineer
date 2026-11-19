package bench.vclock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A replica of a multi-value store using vector clocks. */
public final class Replica {
    private final String id;
    private final Map<String, List<Version>> store = new HashMap<>();

    public Replica(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("bad id");
        this.id = id;
    }

    public String id() {
        return id;
    }

    public record Read(List<String> values, Context context) { }

    /** Immutable vector clock. */
    public static final class Context {
        public static final Context EMPTY = new Context(Map.of());

        private final Map<String, Long> clock;

        private Context(Map<String, Long> clock) {
            this.clock = clock;
        }

        long get(String replica) {
            return clock.getOrDefault(replica, 0L);
        }

        static Context of(Map<String, Long> m) {
            return m.isEmpty() ? EMPTY : new Context(Collections.unmodifiableMap(new TreeMap<>(m)));
        }

        Map<String, Long> entries() {
            return clock;
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
            return "Context" + new TreeMap<>(clock);
        }
    }

    private record Version(Context clock, String value, boolean tombstone) { }

    public synchronized Read get(String key) {
        if (key == null) throw new IllegalArgumentException("key is null");
        List<Version> vs = store.get(key);
        if (vs == null) return new Read(List.of(), Context.EMPTY);
        List<String> values = new ArrayList<>();
        Map<String, Long> max = new HashMap<>();
        for (Version v : vs) {
            if (!v.tombstone()) values.add(v.value());
            for (Map.Entry<String, Long> e : v.clock().entries().entrySet()) max.merge(e.getKey(), e.getValue(), Math::max);
        }
        Collections.sort(values);
        return new Read(Collections.unmodifiableList(values), Context.of(max));
    }

    public synchronized void put(String key, String value, Context context) {
        if (key == null || value == null || context == null) throw new IllegalArgumentException("null argument");
        write(key, value, false, context);
    }

    public synchronized void delete(String key, Context context) {
        if (key == null || context == null) throw new IllegalArgumentException("null argument");
        write(key, null, true, context);
    }

    private void write(String key, String value, boolean tombstone, Context context) {
        List<Version> vs = store.computeIfAbsent(key, k -> new ArrayList<>());
        long n = context.get(id);
        for (Version v : vs) n = Math.max(n, v.clock().get(id));
        Map<String, Long> c = new HashMap<>(context.entries());
        c.put(id, n + 1);
        Context nc = Context.of(c);
        vs.removeIf(v -> v.clock().leq(nc));
        vs.add(new Version(nc, value, tombstone));
    }

    public void syncFrom(Replica other) {
        if (other == null) throw new IllegalArgumentException("other is null");
        if (other == this) return;
        Map<String, List<Version>> snapshot = new HashMap<>();
        synchronized (other) {
            for (Map.Entry<String, List<Version>> e : other.store.entrySet()) snapshot.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        synchronized (this) {
            for (Map.Entry<String, List<Version>> e : snapshot.entrySet()) {
                List<Version> mine = store.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                List<Version> union = new ArrayList<>(mine);
                for (Version v : e.getValue()) {
                    boolean dup = false;
                    for (Version u : union) {
                        if (u.clock().equals(v.clock())) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) union.add(v);
                }
                List<Version> kept = new ArrayList<>();
                for (Version v : union) {
                    boolean dominated = false;
                    for (Version u : union) {
                        if (v.clock().lessThan(u.clock())) {
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
}
