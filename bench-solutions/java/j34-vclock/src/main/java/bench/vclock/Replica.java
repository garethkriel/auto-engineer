package bench.vclock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A replica of a key/value store using vector clocks and siblings. */
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

    private record Version(Context clock, String value, boolean tombstone) { }

    public record Read(List<String> values, Context context) { }

    public static final class Context {
        public static final Context EMPTY = new Context(Map.of());

        private final Map<String, Long> clock;

        private Context(Map<String, Long> clock) {
            this.clock = Map.copyOf(clock);
        }

        long get(String replica) {
            return clock.getOrDefault(replica, 0L);
        }

        boolean lessOrEqual(Context o) {
            for (Map.Entry<String, Long> e : clock.entrySet()) {
                if (e.getValue() > o.get(e.getKey())) return false;
            }
            return true;
        }

        boolean lessThan(Context o) {
            return lessOrEqual(o) && !equals(o);
        }

        Context merge(Context o) {
            Map<String, Long> m = new HashMap<>(clock);
            o.clock.forEach((k, v) -> m.merge(k, v, Math::max));
            return new Context(m);
        }

        Context with(String replica, long counter) {
            Map<String, Long> m = new HashMap<>(clock);
            m.put(replica, counter);
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

    public Read get(String key) {
        if (key == null) throw new IllegalArgumentException("key is null");
        List<Version> vs = store.get(key);
        if (vs == null) return new Read(List.of(), Context.EMPTY);
        List<String> values = new ArrayList<>();
        Context ctx = Context.EMPTY;
        for (Version v : vs) {
            ctx = ctx.merge(v.clock);
            if (!v.tombstone) values.add(v.value);
        }
        Collections.sort(values);
        return new Read(Collections.unmodifiableList(values), ctx);
    }

    public void put(String key, String value, Context context) {
        if (key == null || value == null || context == null) throw new IllegalArgumentException("null argument");
        write(key, value, false, context);
    }

    public void delete(String key, Context context) {
        if (key == null || context == null) throw new IllegalArgumentException("null argument");
        write(key, null, true, context);
    }

    private void write(String key, String value, boolean tombstone, Context context) {
        List<Version> vs = store.computeIfAbsent(key, k -> new ArrayList<>());
        long max = context.get(id);
        for (Version v : vs) max = Math.max(max, v.clock.get(id));
        Context c = context.with(id, max + 1);
        vs.removeIf(v -> v.clock.lessOrEqual(c));
        vs.add(new Version(c, value, tombstone));
    }

    public void syncFrom(Replica other) {
        if (other == null) throw new IllegalArgumentException("other is null");
        if (other == this) return;
        for (Map.Entry<String, List<Version>> e : other.store.entrySet()) {
            List<Version> mine = store.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            List<Version> union = new ArrayList<>();
            Set<Context> seen = new HashSet<>();
            for (Version v : mine) if (seen.add(v.clock)) union.add(v);
            for (Version v : e.getValue()) if (seen.add(v.clock)) union.add(v);
            List<Version> kept = new ArrayList<>();
            for (Version v : union) {
                boolean dominated = false;
                for (Version w : union) {
                    if (v.clock.lessThan(w.clock)) {
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
