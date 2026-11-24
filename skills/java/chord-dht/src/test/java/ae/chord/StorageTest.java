package ae.chord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StorageTest {

    static ChordNetwork stableRing(long seed, int bits, int r, int n) {
        ChordNetwork net = ConvergenceTest.build(new Random(seed), bits, r, n);
        assertTrue(net.stabilize(600) >= 0);
        return net;
    }

    @Test
    void putReplicatesOnOwnerAndNextSuccessors() {
        ChordNetwork net = new ChordNetwork(new Ring(6), 3);
        net.create(1);
        for (long id : new long[] {8, 14, 21, 32}) {
            net.join(id, 1);
            assertTrue(net.stabilize(200) >= 0);
        }
        int copies = net.put(1, 10, "v");
        assertEquals(3, copies);
        // key 10 is owned by 14; replicas on 21 and 32
        assertTrue(net.node(14).storeKeys().contains(10L));
        assertTrue(net.node(21).storeKeys().contains(10L));
        assertTrue(net.node(32).storeKeys().contains(10L));
        assertFalse(net.node(1).storeKeys().contains(10L));
        assertFalse(net.node(8).storeKeys().contains(10L));
        assertEquals(Optional.of("v"), net.get(8, 10));
        assertEquals(Optional.empty(), net.get(8, 11));
    }

    @Test
    void smallRingsHoldAtMostOneCopyPerNode() {
        ChordNetwork net = new ChordNetwork(new Ring(5), 4);
        net.create(2);
        net.join(17, 2);
        assertTrue(net.stabilize(100) >= 0);
        assertEquals(2, net.put(2, 9, "x"));
        assertEquals(List.of(), Invariants.storage(net));
    }

    @Test
    void latestWriteWinsEvenAfterOwnerChanges() {
        ChordNetwork net = new ChordNetwork(new Ring(6), 3);
        net.create(10);
        net.join(30, 10);
        assertTrue(net.stabilize(100) >= 0);
        net.put(10, 25, "old");
        net.put(30, 25, "new");
        assertEquals(Optional.of("new"), net.get(10, 25));
        // a node joins in front of the key: it becomes the new owner and must serve the new value
        net.join(26, 10);
        assertTrue(net.stabilize(100) >= 0);
        assertEquals(26, net.lookup(10, 25).owner());
        assertEquals(Optional.of("new"), net.get(30, 25));
        net.put(10, 25, "newer");
        assertEquals(Optional.of("newer"), net.get(26, 25));
        assertEquals(List.of(), Invariants.storage(net));
    }

    @Test
    void stringKeysAreHashedOntoTheRing() {
        ChordNetwork net = stableRing(11, 16, 3, 20);
        net.put(net.liveIds().first(), "alpha", "1");
        assertEquals(Optional.of("1"), net.get(net.liveIds().last(), "alpha"));
        long id = net.ring().hash("alpha");
        assertTrue(net.node(net.lookup(net.liveIds().first(), id).owner()).storeKeys().contains(id));
    }

    @Test
    void joinTransfersKeysAndStorageStaysExactlyReplicated() {
        Random rnd = new Random(21);
        ChordNetwork net = stableRing(21, 16, 3, 25);
        Map<Long, String> model = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            long key = rnd.nextInt(1 << 16);
            model.put(key, "v" + i);
            net.put(net.liveIds().first(), key, "v" + i);
        }
        assertEquals(List.of(), Invariants.storage(net));
        for (int j = 0; j < 10; j++) {
            long id;
            do {
                id = rnd.nextInt(1 << 16);
            } while (net.liveIds().contains(id));
            net.join(id, net.liveIds().first());
            assertTrue(net.stabilize(600) >= 0);
            assertEquals(List.of(), Invariants.all(net), "after join " + id);
        }
        for (Map.Entry<Long, String> e : model.entrySet()) {
            assertEquals(Optional.of(e.getValue()), net.get(net.liveIds().last(), e.getKey()));
        }
    }

    @Test
    void crashOfUpToRMinusOneAdjacentNodesLosesNothing() {
        for (int adjacent = 1; adjacent <= 2; adjacent++) {
            Random rnd = new Random(100 + adjacent);
            ChordNetwork net = stableRing(100 + adjacent, 16, 3, 30);
            Map<Long, String> model = new HashMap<>();
            for (int i = 0; i < 200; i++) {
                long key = rnd.nextInt(1 << 16);
                model.put(key, "v" + i);
                net.put(net.liveIds().first(), key, "v" + i);
            }
            // crash `adjacent` consecutive nodes at once, with no stabilisation in between
            List<Long> ids = new ArrayList<>(net.liveIds());
            int at = rnd.nextInt(ids.size() - adjacent);
            for (int i = 0; i < adjacent; i++) {
                net.crash(ids.get(at + i));
            }
            assertTrue(net.stabilize(600) >= 0, "adjacent=" + adjacent);
            assertEquals(List.of(), Invariants.all(net), "adjacent=" + adjacent);
            for (Map.Entry<Long, String> e : model.entrySet()) {
                assertEquals(Optional.of(e.getValue()),
                        net.get(net.liveIds().first(), e.getKey()), "key " + e.getKey() + " adjacent=" + adjacent);
            }
        }
    }

    @Test
    void crashOfRAdjacentNodesDoesLoseTheirSharedKeys() {
        // documents the fault model limit: r adjacent crashes can erase every replica of a key
        ChordNetwork net = new ChordNetwork(new Ring(6), 2);
        net.create(1);
        for (long id : new long[] {10, 20, 30, 40}) {
            net.join(id, 1);
            assertTrue(net.stabilize(200) >= 0);
        }
        net.put(1, 15, "doomed"); // owner 20, replica 30
        net.crash(20);
        net.crash(30);
        assertTrue(net.stabilize(200) >= 0);
        assertEquals(Optional.empty(), net.get(1, 15));
        assertEquals(List.of(), Invariants.ring(net));
    }

    @Test
    void gracefulLeaveHandsDataOverImmediately() {
        ChordNetwork net = new ChordNetwork(new Ring(6), 2);
        net.create(1);
        for (long id : new long[] {10, 20, 30}) {
            net.join(id, 1);
            assertTrue(net.stabilize(200) >= 0);
        }
        net.put(1, 15, "kept"); // owner 20, replica 30
        net.leave(20);
        // before any maintenance round (and before any lookup could lazily prune a dead pointer),
        // the neighbours already skip 20 and the successor serves the key
        assertFalse(net.liveIds().contains(20L));
        assertEquals(30L, net.node(10).successorList().get(0));
        assertEquals(10L, net.node(30).predecessor().orElseThrow());
        assertEquals(Optional.of("kept"), net.get(1, 15));
        assertTrue(net.stabilize(200) >= 0);
        assertEquals(List.of(), Invariants.all(net));
    }

    @Test
    void cannotCrashOrLeaveAnUnknownNode() {
        ChordNetwork net = new ChordNetwork(new Ring(5), 2);
        net.create(1);
        assertThrows(IllegalArgumentException.class, () -> net.crash(2));
        assertThrows(IllegalArgumentException.class, () -> net.leave(2));
    }

    @Test
    void joiningNodeTakesItsKeysFromTheSuccessorImmediately() {
        ChordNetwork net = new ChordNetwork(new Ring(6), 2);
        net.create(1);
        net.join(40, 1);
        assertTrue(net.stabilize(200) >= 0);
        net.put(1, 20, "a");  // owner 40
        net.put(1, 30, "b");  // owner 40
        net.put(1, 45, "c");  // owner 1 (wraps)
        // 25 joins between 1 and 40: keys 20 (<=25) must move to it at once, key 30 must stay with 40
        net.join(25, 1);
        assertTrue(net.node(25).storeKeys().contains(20L), "key 20 handed to the new node at join time");
        assertFalse(net.node(25).storeKeys().contains(30L));
        assertFalse(net.node(25).storeKeys().contains(45L));
    }

    @Test
    void checkPredecessorForgetsACrashedPredecessor() {
        ChordNetwork net = new ChordNetwork(new Ring(5), 2);
        net.create(1);
        net.join(10, 1);
        net.join(20, 1);
        assertTrue(net.stabilize(100) >= 0);
        assertEquals(10L, net.node(20).predecessor().orElseThrow());
        net.crash(10);
        assertEquals(10L, net.node(20).predecessor().orElseThrow(), "no one has noticed yet");
        net.node(20).checkPredecessor();
        assertTrue(net.node(20).predecessor().isEmpty());
    }

    @Test
    void olderVersionNeverOverwritesNewerOnAReplica() {
        ChordNetwork net = new ChordNetwork(new Ring(5), 2);
        net.create(1);
        net.join(16, 1);
        assertTrue(net.stabilize(100) >= 0);
        net.put(1, 8, "v1");
        net.put(1, 8, "v2");
        net.node(16).storeLocal(8, new Versioned(0, "stale"));
        assertEquals("v2", net.node(16).stored(8).orElseThrow().value());
        assertEquals(Optional.of("v2"), net.get(1, 8));
    }

    @Test
    void readReturnsTheNewestVersionAmongOwnerAndReplicas() {
        ChordNetwork net = new ChordNetwork(new Ring(5), 2);
        net.create(1);
        net.join(16, 1);
        assertTrue(net.stabilize(100) >= 0);
        net.put(1, 8, "v1");                                   // owner 16, replica 1
        net.node(1).storeLocal(8, new Versioned(1000, "fresh")); // only the replica holds the newer write
        assertEquals(Optional.of("fresh"), net.get(16, 8));
    }
}
