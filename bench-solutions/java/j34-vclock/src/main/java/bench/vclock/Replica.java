package bench.vclock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A replica of a key/value store using vector clocks and tombstones. */
public final class Replica {
    private record Version(Context clock, String value, boolean tombstone) {}

    private final String id;
    private final Map<String, List<Version>> store = new HashMap<>();

    public Replica(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id");
        this.id = id;
    }

    public String id() {
        return id;
    }

    public record Read(List<String> values, Context context) {}

    public static final class Context {
        public static final Context EMPTY = new Context(Map.of());

        private final Map<String, Long> clock;

        private Context(Map<String, Long> clock) {
            this.clock = Map.copyOf(clock);
        }

        long get(String r) {
            return clock.getOrDefault(r, 0L);
        }

        boolean leq(Context o) {
            for (var e : clock.entrySet()) {
                if (e.getValue() > o.get(e.getKey())) return false;
            }
            return true;
        }

        boolean lt(Context o) {
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
            return "Context" + new java.util.TreeMap<>(clock);
        }
    }

    public synchronized Read get(String key) {
        if (key == null) throw new IllegalArgumentException("key");
        List<Version> vs = store.getOrDefault(key, List.of());
        List<String> values = new ArrayList<>();
        Map<String, Long> max = new HashMap<>();
        for (Version v : vs) {
            if (!v.tombstone) values.add(v.value);
            for (var e : v.clock.clock.entrySet()) max.merge(e.getKey(), e.getValue(), Math::max);
        }
        Collections.sort(values);
        return new Read(Collections.unmodifiableList(values), new Context(max));
    }

    public synchronized void put(String key, String value, Context context) {
        if (key == null || value == null || context == null) throw new IllegalArgumentException("argument");
        write(key, value, false, context);
    }

    public synchronized void delete(String key, Context context) {
        if (key == null || context == null) throw new IllegalArgumentException("argument");
        write(key, null, true, context);
    }

    private void write(String key, String value, boolean tombstone, Context context) {
        List<Version> vs = store.computeIfAbsent(key, k -> new ArrayList<>());
        long m = context.get(id);
        for (Version v : vs) m = Math.max(m, v.clock.get(id));
        Map<String, Long> c = new HashMap<>(context.clock);
        c.put(id, m + 1);
        Context nc = new Context(c);
        vs.removeIf(v -> v.clock.leq(nc));
        vs.add(new Version(nc, value, tombstone));
    }

    public void syncFrom(Replica other) {
        if (other == null) throw new IllegalArgumentException("other");
        if (other == this) return;
        Map<String, List<Version>> snapshot;
        synchronized (other) {
            snapshot = new HashMap<>();
            for (var e : other.store.entrySet()) snapshot.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        synchronized (this) {
            for (var e : snapshot.entrySet()) {
                List<Version> mine = store.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                List<Version> union = new ArrayList<>(mine);
                Set<Context> seen = new HashSet<>();
                for (Version v : mine) seen.add(v.clock);
                for (Version v : e.getValue()) {
                    if (seen.add(v.clock)) union.add(v);
                }
                List<Version> kept = new ArrayList<>();
                for (Version v : union) {
                    boolean dominated = false;
                    for (Version w : union) {
                        if (v.clock.lt(w.clock)) {
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
