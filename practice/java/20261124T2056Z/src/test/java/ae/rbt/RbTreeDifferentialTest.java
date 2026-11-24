package ae.rbt;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Randomised comparison against {@link TreeMap}; the invariant checker runs after every mutation. */
class RbTreeDifferentialTest {

    private static Optional<Integer> opt(Integer k) {
        return Optional.ofNullable(k);
    }

    private static void run(long seed, int keyRange, int steps) {
        Random rnd = new Random(seed);
        RbTreeMap<Integer, Integer> m = new RbTreeMap<>();
        TreeMap<Integer, Integer> o = new TreeMap<>();
        for (int step = 0; step < steps; step++) {
            int k = rnd.nextInt(keyRange);
            int op = rnd.nextInt(100);
            String ctx = "seed " + seed + " step " + step + " key " + k;
            if (op < 40) {
                int v = rnd.nextInt();
                assertEquals(o.put(k, v), m.put(k, v), ctx);
                m.check();
            } else if (op < 70) {
                assertEquals(o.remove(k), m.remove(k), ctx);
                m.check();
            } else if (op < 75) {
                Map.Entry<Integer, Integer> e = o.pollFirstEntry();
                Optional<Map.Entry<Integer, Integer>> got = m.pollFirst();
                assertEquals(Optional.ofNullable(e == null ? null : Map.entry(e.getKey(), e.getValue())), got, ctx);
                m.check();
            } else {
                assertEquals(o.get(k), m.get(k), ctx);
                assertEquals(o.containsKey(k), m.containsKey(k), ctx);
                assertEquals(opt(o.floorKey(k)), m.floorKey(k), ctx);
                assertEquals(opt(o.ceilingKey(k)), m.ceilingKey(k), ctx);
                assertEquals(opt(o.lowerKey(k)), m.lowerKey(k), ctx);
                assertEquals(opt(o.higherKey(k)), m.higherKey(k), ctx);
                assertEquals(o.headMap(k, false).size(), m.rank(k), ctx);
                int hi = rnd.nextInt(keyRange);
                int expected = k > hi ? 0 : o.subMap(k, true, hi, true).size();
                assertEquals(expected, m.countRange(k, hi), ctx + " hi " + hi);
                if (k <= hi) {
                    List<Map.Entry<Integer, Integer>> want = new ArrayList<>();
                    for (Map.Entry<Integer, Integer> e : o.subMap(k, true, hi, true).entrySet()) {
                        want.add(Map.entry(e.getKey(), e.getValue()));
                    }
                    assertEquals(want, m.entries(k, hi), ctx + " hi " + hi);
                }
            }
            assertEquals(o.size(), m.size(), ctx);
        }
        assertEquals(new ArrayList<>(o.keySet()), m.keys(), "final keys, seed " + seed);
        int i = 0;
        for (Integer key : o.keySet()) {
            assertEquals(key, m.select(i), "select " + i + " seed " + seed);
            assertEquals(i, m.rank(key), "rank of " + key + " seed " + seed);
            i++;
        }
        if (!o.isEmpty()) {
            assertEquals(o.firstKey(), m.firstKey());
            assertEquals(o.lastKey(), m.lastKey());
        }
    }

    @Test
    void smallKeySpaceForcesCollisionsAndDeleteHeavyPaths() {
        for (long seed = 1; seed <= 12; seed++) {
            run(seed, 40, 3_000);
        }
    }

    @Test
    void mediumKeySpaceGrowsDeeperTrees() {
        for (long seed = 100; seed < 106; seed++) {
            run(seed, 400, 4_000);
        }
    }

    @Test
    void drainingInEveryOrderKeepsInvariants() {
        int n = 300;
        for (int mode = 0; mode < 3; mode++) {
            RbTreeMap<Integer, Integer> m = new RbTreeMap<>();
            List<Integer> keys = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                m.put(i, i);
                keys.add(i);
            }
            if (mode == 1) {
                java.util.Collections.reverse(keys);
            } else if (mode == 2) {
                java.util.Collections.shuffle(keys, new Random(7));
            }
            int left = n;
            for (int k : keys) {
                assertEquals(k, m.remove(k), "mode " + mode + " key " + k);
                left--;
                m.check();
                assertEquals(left, m.size());
            }
            assertTrue(m.isEmpty());
        }
    }
}
