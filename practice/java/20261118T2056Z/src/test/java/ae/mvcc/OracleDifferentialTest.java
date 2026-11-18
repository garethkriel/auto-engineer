package ae.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Compares the store with a naive model that copies the whole map at begin. */
class OracleDifferentialTest {

    /** Naive reference model. */
    private static final class Model {
        final TreeMap<String, String> committed = new TreeMap<>();
        final Map<String, Long> lastWrite = new HashMap<>();
        long clock;

        final class Tx {
            final Isolation iso;
            final long start = clock;
            final TreeMap<String, String> snap = new TreeMap<>(committed);
            final Map<String, Optional<String>> writes = new HashMap<>();
            final Set<String> reads = new HashSet<>();
            final List<String[]> ranges = new ArrayList<>();

            Tx(Isolation iso) {
                this.iso = iso;
            }

            Optional<String> get(String k) {
                reads.add(k);
                if (writes.containsKey(k)) {
                    return writes.get(k);
                }
                return Optional.ofNullable(snap.get(k));
            }

            TreeMap<String, String> scan(String from, String to) {
                ranges.add(new String[] {from, to});
                TreeMap<String, String> out = new TreeMap<>();
                for (Map.Entry<String, String> e : snap.entrySet()) {
                    if (e.getKey().compareTo(from) >= 0 && e.getKey().compareTo(to) < 0) {
                        out.put(e.getKey(), e.getValue());
                    }
                }
                for (Map.Entry<String, Optional<String>> w : writes.entrySet()) {
                    if (w.getKey().compareTo(from) >= 0 && w.getKey().compareTo(to) < 0) {
                        if (w.getValue().isPresent()) {
                            out.put(w.getKey(), w.getValue().get());
                        } else {
                            out.remove(w.getKey());
                        }
                    }
                }
                return out;
            }

            /** Returns the commit ts, or -1 for a conflict. */
            long commit() {
                if (writes.isEmpty()) {
                    return start;
                }
                for (String k : writes.keySet()) {
                    if (lastWrite.getOrDefault(k, 0L) > start) {
                        return -1;
                    }
                }
                if (iso == Isolation.SERIALIZABLE) {
                    for (String k : reads) {
                        if (lastWrite.getOrDefault(k, 0L) > start) {
                            return -1;
                        }
                    }
                    for (String[] r : ranges) {
                        for (Map.Entry<String, Long> e : lastWrite.entrySet()) {
                            if (e.getKey().compareTo(r[0]) >= 0 && e.getKey().compareTo(r[1]) < 0
                                    && e.getValue() > start) {
                                return -1;
                            }
                        }
                    }
                }
                long ts = ++clock;
                for (Map.Entry<String, Optional<String>> w : writes.entrySet()) {
                    lastWrite.put(w.getKey(), ts);
                    if (w.getValue().isPresent()) {
                        committed.put(w.getKey(), w.getValue().get());
                    } else {
                        committed.remove(w.getKey());
                    }
                }
                return ts;
            }
        }
    }

    private static String key(Random r) {
        return String.format("k%02d", r.nextInt(10));
    }

    @Test
    void randomInterleavingsMatchModel() {
        for (long seed = 1; seed <= 60; seed++) {
            Random r = new Random(seed);
            MvccStore<String, String> store = new MvccStore<>();
            Model model = new Model();
            List<Transaction<String, String>> real = new ArrayList<>();
            List<Model.Tx> mod = new ArrayList<>();
            int counter = 0;
            for (int step = 0; step < 400; step++) {
                String ctx = "seed " + seed + " step " + step;
                int op = r.nextInt(100);
                if (real.isEmpty() || (op < 12 && real.size() < 5)) {
                    Isolation iso = r.nextBoolean() ? Isolation.SNAPSHOT : Isolation.SERIALIZABLE;
                    real.add(store.begin(iso));
                    mod.add(model.new Tx(iso));
                    continue;
                }
                int i = r.nextInt(real.size());
                Transaction<String, String> t = real.get(i);
                Model.Tx m = mod.get(i);
                if (op < 40) {
                    String k = key(r);
                    assertEquals(m.get(k), t.get(k), ctx + " get " + k);
                } else if (op < 60) {
                    String k = key(r);
                    String v = "v" + (counter++);
                    t.put(k, v);
                    m.writes.put(k, Optional.of(v));
                } else if (op < 68) {
                    String k = key(r);
                    t.delete(k);
                    m.writes.put(k, Optional.empty());
                } else if (op < 78) {
                    String a = key(r);
                    String b = key(r);
                    assertEquals(m.scan(a, b), t.scan(a, b), ctx + " scan " + a + ".." + b);
                } else if (op < 90) {
                    long expected = m.commit();
                    if (expected < 0) {
                        try {
                            t.commit();
                            throw new AssertionError(ctx + ": expected conflict");
                        } catch (ConflictException e) {
                            // expected
                        }
                    } else {
                        assertEquals(expected, t.commit(), ctx + " commit ts");
                    }
                    real.remove(i);
                    mod.remove(i);
                    try (Transaction<String, String> peek = store.begin()) {
                        assertEquals(model.committed, peek.scan("", "~"), ctx + " state");
                    }
                } else if (op < 95) {
                    t.rollback();
                    real.remove(i);
                    mod.remove(i);
                } else {
                    store.vacuum();
                }
                assertEquals(real.size(), store.activeTransactions(), ctx);
            }
        }
    }

}
