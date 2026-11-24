package ae.chord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ConvergenceTest {

    /** Distinct random ids on a ring. */
    static List<Long> randomIds(Random rnd, Ring ring, int n) {
        TreeSet<Long> ids = new TreeSet<>();
        while (ids.size() < n) {
            ids.add((long) rnd.nextInt((int) ring.size()));
        }
        List<Long> list = new ArrayList<>(ids);
        java.util.Collections.shuffle(list, rnd);
        return list;
    }

    static ChordNetwork build(Random rnd, int bits, int r, int n) {
        Ring ring = new Ring(bits);
        ChordNetwork net = new ChordNetwork(ring, r);
        List<Long> ids = randomIds(rnd, ring, n);
        net.create(ids.get(0));
        for (int i = 1; i < n; i++) {
            long via = new ArrayList<>(net.liveIds()).get(rnd.nextInt(net.liveIds().size()));
            net.join(ids.get(i), via);
            if (i % 4 == 0) {
                net.round(); // interleave joins with maintenance, as a real system would
            }
        }
        return net;
    }

    @Test
    void singleNodeOwnsEverything() {
        ChordNetwork net = new ChordNetwork(new Ring(5), 3);
        net.create(7);
        assertEquals(0, net.stabilize(5));
        for (long k = 0; k < 32; k++) {
            Lookup l = net.lookup(7, k);
            assertEquals(7, l.owner());
            assertEquals(0, l.hops());
        }
        assertEquals(List.of(7L), net.node(7).successorList());
        assertTrue(net.node(7).predecessor().isEmpty() || net.node(7).predecessor().get() == 7);
    }

    @Test
    void twoNodesFindEachOther() {
        ChordNetwork net = new ChordNetwork(new Ring(5), 3);
        net.create(3);
        net.join(20, 3);
        int rounds = net.stabilize(100);
        assertTrue(rounds >= 0);
        assertEquals(List.of(20L), net.node(3).successorList());
        assertEquals(List.of(3L), net.node(20).successorList());
        assertEquals(20L, net.node(3).predecessor().orElseThrow());
        assertEquals(3L, net.node(20).predecessor().orElseThrow());
        assertEquals(20, net.lookup(3, 10).owner());
        assertEquals(3, net.lookup(20, 25).owner());
    }

    @Test
    void joiningNeedsAnExistingNodeAndAFreeId() {
        ChordNetwork net = new ChordNetwork(new Ring(5), 2);
        assertThrows(IllegalArgumentException.class, () -> net.join(4, 9));
        net.create(4);
        assertThrows(IllegalArgumentException.class, () -> net.create(5));
        assertThrows(IllegalArgumentException.class, () -> net.join(4, 4));
        assertThrows(IllegalArgumentException.class, () -> net.join(40, 4));
        assertThrows(IllegalArgumentException.class, () -> net.join(9, 12));
    }

    @Test
    void randomRingsConvergeToTheIdealRing() {
        for (int seed = 1; seed <= 6; seed++) {
            Random rnd = new Random(seed);
            ChordNetwork net = build(rnd, 16, 3, 40);
            int rounds = net.stabilize(400);
            assertTrue(rounds >= 0, "seed " + seed + " did not converge: " + Invariants.all(net));
            assertEquals(List.of(), Invariants.all(net), "seed " + seed);
            assertTrue(rounds <= 200, "seed " + seed + " needed " + rounds + " rounds");
        }
    }

    @Test
    void lookupsFromEveryNodeMatchTheOracle() {
        Random rnd = new Random(77);
        ChordNetwork net = build(rnd, 16, 3, 50);
        assertTrue(net.stabilize(400) >= 0);
        RingOracle oracle = new RingOracle(net.ring(), net.liveIds());
        for (long start : net.liveIds()) {
            for (int i = 0; i < 40; i++) {
                long key = rnd.nextInt(1 << 16);
                Lookup l = net.lookup(start, key);
                assertEquals(oracle.successor(key), l.owner(), "start " + start + " key " + key);
                assertEquals(start, l.path().get(0));
                assertEquals(l.hops() + 1, l.path().size());
            }
        }
    }

    @Test
    void meanHopsAreLogarithmicNotLinear() {
        Random rnd = new Random(5);
        int n = 128;
        ChordNetwork net = build(rnd, 20, 3, n);
        assertTrue(net.stabilize(600) >= 0);
        List<Long> ids = new ArrayList<>(net.liveIds());
        long total = 0;
        int max = 0;
        int lookups = 4000;
        for (int i = 0; i < lookups; i++) {
            Lookup l = net.lookup(ids.get(rnd.nextInt(n)), rnd.nextInt(1 << 20));
            total += l.hops();
            max = Math.max(max, l.hops());
        }
        double mean = total / (double) lookups;
        // theory: about (1/2) log2 n = 3.5 hops; a successor-only walk would average n/2 = 64
        assertTrue(mean <= 7.0, "mean hops " + mean);
        assertTrue(mean >= 1.5, "suspiciously low mean hops " + mean);
        assertTrue(max <= 20, "max hops " + max);
    }

    @Test
    void lookupsAreCorrectWhileFingersAreStillBeingFilled() {
        // fingers fill one per round per node, so right after joins many are stale or unset: routing must still be correct
        Random rnd = new Random(3);
        Ring ring = new Ring(12);
        ChordNetwork net = new ChordNetwork(ring, 3);
        List<Long> ids = randomIds(rnd, ring, 20);
        net.create(ids.get(0));
        for (int i = 1; i < ids.size(); i++) {
            net.join(ids.get(i), ids.get(0));
            for (int k = 0; k < 4; k++) {
                net.round(); // ring pointers settle quickly, fingers lag
            }
        }
        // ring pointers are now right even though only some fingers are fixed
        for (int i = 0; i < 6 * 12; i++) {
            net.round();
        }
        RingOracle oracle = new RingOracle(ring, net.liveIds());
        for (long key = 0; key < 4096; key += 37) {
            assertEquals(oracle.successor(key), net.lookup(ids.get(0), key).owner());
        }
    }

    @Test
    void messageCounterGrowsWithWork() {
        ChordNetwork net = build(new Random(9), 12, 3, 10);
        long before = net.messages();
        net.lookup(net.liveIds().first(), 123);
        assertTrue(net.messages() > before);
    }
}
