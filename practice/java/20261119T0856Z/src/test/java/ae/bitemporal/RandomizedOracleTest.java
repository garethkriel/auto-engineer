package ae.bitemporal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Differential test: the store against a brute-force array-per-transaction oracle. */
class RandomizedOracleTest {

    /** Valid times 0..N-1 are real cells; cell N stands for the whole range [N, FOREVER). */
    private static final int N = 24;
    private static final String[] KEYS = {"k0", "k1", "k2"};
    private static final String[] VALUES = {"A", "B", "C"};

    /** oracle state: [key][cell] -> value or null. */
    private static String[][] copy(String[][] s) {
        String[][] c = new String[s.length][];
        for (int i = 0; i < s.length; i++) {
            c[i] = s[i].clone();
        }
        return c;
    }

    private static void oracleAssert(String[][] s, int k, long from, long to, String v) {
        for (int c = (int) from; c <= (to == Segment.FOREVER ? N : to - 1); c++) {
            s[k][c] = v;
        }
    }

    /** Runs of equal non-null cells, translated to segments (last cell N means FOREVER). */
    private static List<Segment<String>> oracleSegments(String[] cells) {
        List<Segment<String>> out = new ArrayList<>();
        int c = 0;
        while (c <= N) {
            if (cells[c] == null) {
                c++;
                continue;
            }
            int e = c;
            while (e + 1 <= N && cells[c].equals(cells[e + 1])) {
                e++;
            }
            out.add(new Segment<>(c, e == N ? Segment.FOREVER : e + 1, cells[c]));
            c = e + 1;
        }
        return out;
    }

    private static List<Delta<String>> oracleDiff(String[] a, String[] b) {
        List<Delta<String>> out = new ArrayList<>();
        int c = 0;
        while (c <= N) {
            if (java.util.Objects.equals(a[c], b[c])) {
                c++;
                continue;
            }
            int e = c;
            while (e + 1 <= N && java.util.Objects.equals(a[e + 1], a[c])
                    && java.util.Objects.equals(b[e + 1], b[c])) {
                e++;
            }
            out.add(new Delta<>(c, e == N ? Segment.FOREVER : e + 1, a[c], b[c]));
            c = e + 1;
        }
        return out;
    }

    private static void checkInvariants(List<Segment<String>> segs) {
        for (int i = 0; i < segs.size(); i++) {
            Segment<String> s = segs.get(i);
            assertTrue(s.from() < s.to(), "empty segment in " + segs);
            if (i > 0) {
                Segment<String> p = segs.get(i - 1);
                assertTrue(p.to() <= s.from(), "overlap/unsorted in " + segs);
                if (p.to() == s.from()) {
                    assertNotEquals(p.value(), s.value(), "uncoalesced in " + segs);
                }
            }
        }
    }

    @Test
    void storeAgreesWithOracleAcrossSeededRuns() {
        int totalTx = 0;
        int noOps = 0;
        for (long seed = 1; seed <= 60; seed++) {
            Random rnd = new Random(seed);
            long[] clock = {0};
            BitemporalStore<String, String> store = new BitemporalStore<>(() -> clock[0]);
            List<String[][]> states = new ArrayList<>();
            List<Long> stamps = new ArrayList<>();
            states.add(new String[KEYS.length][N + 1]);
            stamps.add(-1L);
            String[][] live = states.get(0);

            for (int step = 0; step < 40; step++) {
                clock[0] += rnd.nextInt(3);
                String[][] next = copy(live);
                int ops = 1 + rnd.nextInt(3);
                long before = store.currentTx();
                long tx = store.transact(t -> {
                    for (int o = 0; o < ops; o++) {
                        int k = rnd.nextInt(KEYS.length);
                        long from = rnd.nextInt(N);
                        long to = rnd.nextInt(6) == 0 ? Segment.FOREVER : from + 1 + rnd.nextInt(N - (int) from);
                        if (rnd.nextInt(4) == 0) {
                            t.retract(KEYS[k], from, to);
                            oracleAssert(next, k, from, to, null);
                        } else {
                            String v = VALUES[rnd.nextInt(VALUES.length)];
                            t.assertValue(KEYS[k], from, to, v);
                            oracleAssert(next, k, from, to, v);
                        }
                    }
                });
                boolean changed = !java.util.Arrays.deepEquals(live, next);
                if (changed) {
                    assertEquals(before + 1, tx, "seed " + seed + " step " + step);
                    states.add(next);
                    stamps.add(clock[0]);
                    live = next;
                    totalTx++;
                } else {
                    assertEquals(before, tx, "no-op must not be recorded, seed " + seed);
                    noOps++;
                }
                assertEquals(states.size() - 1, store.currentTx());
                for (String key : KEYS) {
                    checkInvariants(store.history(key));
                }
            }

            // every tx, key and valid time
            for (int tx = 0; tx < states.size(); tx++) {
                for (int k = 0; k < KEYS.length; k++) {
                    assertEquals(oracleSegments(states.get(tx)[k]), store.history(KEYS[k], tx),
                            "history seed " + seed + " tx " + tx + " key " + k);
                    for (int c = 0; c <= N; c++) {
                        long t = c;
                        assertEquals(Optional.ofNullable(states.get(tx)[k][c]),
                                store.valueAt(KEYS[k], t, tx));
                    }
                }
                final int fTx = tx;
                for (int c = 0; c <= N; c += 5) {
                    var snap = store.snapshot(c, fTx);
                    for (int k = 0; k < KEYS.length; k++) {
                        String want = states.get(fTx)[k][c];
                        assertEquals(want, snap.get(KEYS[k]), "snapshot seed " + seed);
                    }
                }
            }

            // diffs for random tx pairs
            for (int i = 0; i < 15; i++) {
                int a = rnd.nextInt(states.size());
                int b = rnd.nextInt(states.size());
                int k = rnd.nextInt(KEYS.length);
                assertEquals(oracleDiff(states.get(a)[k], states.get(b)[k]), store.diff(KEYS[k], a, b),
                        "diff seed " + seed + " " + a + "->" + b);
            }

            // revisions for random instants
            for (int i = 0; i < 10; i++) {
                int k = rnd.nextInt(KEYS.length);
                int c = rnd.nextInt(N + 1);
                List<Revision<String>> want = new ArrayList<>();
                String last = null;
                for (int tx = 1; tx < states.size(); tx++) {
                    String v = states.get(tx)[k][c];
                    if (v != null && !v.equals(last) || v == null && last != null) {
                        want.add(new Revision<>(tx, stamps.get(tx), v));
                    }
                    last = v;
                }
                assertEquals(want, store.revisions(KEYS[k], c), "revisions seed " + seed);
            }

            // txAtTime against the stamps
            for (long ts = 0; ts <= clock[0] + 1; ts++) {
                int want = 0;
                for (int tx = 1; tx < states.size(); tx++) {
                    if (stamps.get(tx) <= ts) {
                        want = tx;
                    }
                }
                assertEquals(want, store.txAtTime(ts), "txAtTime seed " + seed + " ts " + ts);
            }
        }
        // The generator must exercise both branches, otherwise the test proves little.
        assertTrue(totalTx > 1000, "too few recorded transactions: " + totalTx);
        assertTrue(noOps > 20, "too few no-op transactions: " + noOps);
    }

    @Test
    void failedRandomTransactionsLeaveNoTrace() {
        Random rnd = new Random(99);
        long[] clock = {0};
        BitemporalStore<String, String> store = new BitemporalStore<>(() -> clock[0]);
        store.assertValue("k0", 0, 10, "A");
        List<Segment<String>> before = store.history("k0");
        for (int i = 0; i < 50; i++) {
            int failAt = rnd.nextInt(3);
            assertThrows(IllegalArgumentException.class, () -> store.transact(t -> {
                for (int o = 0; o < 3; o++) {
                    if (o == failAt) {
                        t.assertValue("k0", 5, 5, "Z");
                    }
                    t.assertValue("k" + o, 0, 20, "Z");
                }
            }));
        }
        assertEquals(1, store.currentTx());
        assertEquals(before, store.history("k0"));
        assertTrue(store.history("k1").isEmpty());
    }
}
