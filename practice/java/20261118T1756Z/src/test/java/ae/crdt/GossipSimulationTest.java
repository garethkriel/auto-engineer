package ae.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Replicas apply random local ops and gossip random merges; after full exchange all agree. */
class GossipSimulationTest {
    private static final int REPLICAS = 5;

    @Test
    void pnCounterConvergesToOracleSum() {
        for (long seed = 1; seed <= 40; seed++) {
            Random rnd = new Random(seed);
            PNCounter[] st = new PNCounter[REPLICAS];
            java.util.Arrays.fill(st, PNCounter.empty());
            long oracle = 0;
            for (int step = 0; step < 200; step++) {
                int r = rnd.nextInt(REPLICAS);
                if (rnd.nextInt(3) == 0) {
                    int from = rnd.nextInt(REPLICAS);
                    st[r] = st[r].merge(st[from]);
                } else {
                    int n = rnd.nextInt(5);
                    if (rnd.nextBoolean()) {
                        st[r] = st[r].increment("r" + r, n);
                        oracle += n;
                    } else {
                        st[r] = st[r].decrement("r" + r, n);
                        oracle -= n;
                    }
                }
            }
            PNCounter all = PNCounter.empty();
            for (PNCounter s : st) {
                all = all.merge(s);
            }
            assertEquals(oracle, all.value(), "seed " + seed);
            for (PNCounter s : st) {
                assertEquals(all, s.merge(all), "seed " + seed);
            }
        }
    }

    @Test
    void orSetConvergesAndMatchesAddWinsOracle() {
        for (long seed = 1; seed <= 60; seed++) {
            Random rnd = new Random(seed);
            @SuppressWarnings("unchecked")
            OrSet<Integer>[] st = new OrSet[REPLICAS];
            java.util.Arrays.fill(st, OrSet.<Integer>empty());
            // oracle: all add tags ever made per element, and which tags each remove observed
            Map<Integer, Set<String>> tagsOf = new HashMap<>();
            Set<String> dead = new HashSet<>();
            @SuppressWarnings("unchecked")
            Set<String>[] seen = new Set[REPLICAS];
            for (int i = 0; i < REPLICAS; i++) {
                seen[i] = new HashSet<>();
            }
            int[] counter = new int[REPLICAS];
            for (int step = 0; step < 150; step++) {
                int r = rnd.nextInt(REPLICAS);
                int op = rnd.nextInt(10);
                int e = rnd.nextInt(4);
                if (op < 4) {
                    st[r] = st[r].add(e, "r" + r);
                    String tag = "r" + r + ":" + (++counter[r]);
                    tagsOf.computeIfAbsent(e, k -> new HashSet<>()).add(tag);
                    seen[r].add(tag + "@" + e);
                } else if (op < 6) {
                    st[r] = st[r].remove(e);
                    for (String t : seen[r]) {
                        if (t.endsWith("@" + e)) {
                            dead.add(t);
                        }
                    }
                } else {
                    int from = rnd.nextInt(REPLICAS);
                    st[r] = st[r].merge(st[from]);
                    seen[r].addAll(seen[from]);
                }
            }
            OrSet<Integer> all = OrSet.empty();
            for (OrSet<Integer> s : st) {
                all = all.merge(s);
            }
            Set<Integer> expected = new HashSet<>();
            for (var en : tagsOf.entrySet()) {
                for (String tag : en.getValue()) {
                    if (!dead.contains(tag + "@" + en.getKey())) {
                        expected.add(en.getKey());
                    }
                }
            }
            assertEquals(expected, all.elements(), "seed " + seed);
            for (OrSet<Integer> s : st) {
                assertEquals(all, s.merge(all), "converged state, seed " + seed);
                assertEquals(all, all.merge(s), "seed " + seed);
            }
        }
    }

    @Test
    void orSetMergeLawsOnRandomStates() {
        Random rnd = new Random(7);
        for (int i = 0; i < 200; i++) {
            OrSet<Integer> x = randomSet(rnd);
            OrSet<Integer> y = randomSet(rnd);
            OrSet<Integer> z = randomSet(rnd);
            assertEquals(x.merge(y), y.merge(x));
            assertEquals(x.merge(y).merge(z), x.merge(y.merge(z)));
            assertEquals(x, x.merge(x));
        }
    }

    private static OrSet<Integer> randomSet(Random rnd) {
        OrSet<Integer> s = OrSet.empty();
        for (int k = rnd.nextInt(8); k > 0; k--) {
            if (rnd.nextInt(3) == 0) {
                s = s.remove(rnd.nextInt(4));
            } else {
                s = s.add(rnd.nextInt(4), "r" + rnd.nextInt(3));
            }
        }
        return s;
    }
}
