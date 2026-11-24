package ae.chord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Seeded random joins, crashes, leaves and writes checked against a plain map and the ring oracle. */
class ChurnTest {

    private static String run(long seed, int bits, int r, int steps) {
        Random rnd = new Random(seed);
        Ring ring = new Ring(bits);
        ChordNetwork net = new ChordNetwork(ring, r);
        Map<Long, String> model = new HashMap<>();
        net.create(rnd.nextInt((int) ring.size()));
        int joins = 0;
        int crashes = 0;
        int leaves = 0;
        int writes = 0;
        for (int step = 0; step < steps; step++) {
            List<Long> live = new ArrayList<>(net.liveIds());
            long any = live.get(rnd.nextInt(live.size()));
            int dice = rnd.nextInt(10);
            if (dice < 4 || live.size() < r + 2) {
                long id = rnd.nextInt((int) ring.size());
                if (!net.liveIds().contains(id)) {
                    net.join(id, any);
                    joins++;
                }
            } else if (dice < 6) {
                net.crash(any);
                crashes++;
                // a burst: sometimes also kill the next ring neighbour(s), but never r in a row
                int extra = rnd.nextInt(r); // 0..r-1 more... capped below so the burst is at most r-1 nodes
                int idx = live.indexOf(any);
                for (int e = 1; e <= extra && e < r - 1 && live.size() - e > r + 1; e++) {
                    net.crash(live.get((idx + e) % live.size()));
                    crashes++;
                }
            } else if (dice < 7) {
                net.leave(any);
                leaves++;
            } else {
                for (int w = 0; w < 5; w++) {
                    long key = rnd.nextInt((int) ring.size());
                    String v = "s" + step + "." + w;
                    net.put(any, key, v);
                    model.put(key, v);
                    writes++;
                }
            }
            assertTrue(net.stabilize(800) >= 0, "seed " + seed + " step " + step + " did not converge");
            List<String> bad = Invariants.all(net);
            assertEquals(List.of(), bad, "seed " + seed + " step " + step);
            RingOracle oracle = new RingOracle(ring, net.liveIds());
            for (int i = 0; i < 5; i++) {
                long key = rnd.nextInt((int) ring.size());
                assertEquals(oracle.successor(key), net.lookup(net.liveIds().first(), key).owner());
            }
        }
        for (Map.Entry<Long, String> e : model.entrySet()) {
            assertEquals(Optional.of(e.getValue()), net.get(net.liveIds().first(), e.getKey()),
                    "seed " + seed + " key " + e.getKey());
        }
        return joins + "/" + crashes + "/" + leaves + "/" + writes;
    }

    @Test
    void churnWithReplication3() {
        for (long seed = 1; seed <= 4; seed++) {
            String mix = run(seed, 14, 3, 60);
            String[] parts = mix.split("/");
            assertTrue(Integer.parseInt(parts[0]) > 5, "joins " + mix);
            assertTrue(Integer.parseInt(parts[1]) > 3, "crashes " + mix);
            assertTrue(Integer.parseInt(parts[2]) > 0, "leaves " + mix);
            assertTrue(Integer.parseInt(parts[3]) > 20, "writes " + mix);
        }
    }

    @Test
    void churnWithReplication4OnASmallRing() {
        for (long seed = 11; seed <= 13; seed++) {
            run(seed, 8, 4, 60);
        }
    }
}
