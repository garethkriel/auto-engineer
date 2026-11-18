package ae.ring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class HashRingTest {

    /** Hasher backed by a table: ring points and keys get exact raw positions. */
    private static Hasher table(Map<String, Long> m) {
        return s -> {
            Long v = m.get(s);
            if (v == null) throw new IllegalStateException("no position for " + s);
            return v;
        };
    }

    /** A owns positions 10,40 ; B owns 20,50 (1 vnode per weight unit, weight 2). */
    private HashRing fixture() {
        Map<String, Long> m = new HashMap<>();
        m.put("A#0", 10L); m.put("A#1", 40L);
        m.put("B#0", 20L); m.put("B#1", 50L);
        m.put("k5", 5L); m.put("k10", 10L); m.put("k15", 15L); m.put("k45", 45L);
        m.put("k55", 55L); m.put("k20", 20L);
        return HashRing.empty(table(m), 1).withNode("A", 2).withNode("B", 2);
    }

    @Test
    void emptyRingHasNoOwner() {
        HashRing r = HashRing.empty();
        assertTrue(r.owner("x").isEmpty());
        assertTrue(r.preferenceList("x", 3).isEmpty());
        assertTrue(r.share().isEmpty());
    }

    @Test
    void ownerIsFirstPointAtOrAfterKey() {
        HashRing r = fixture();
        assertEquals("A", r.owner("k5").orElseThrow());   // 5 -> point 10 (A)
        assertEquals("A", r.owner("k10").orElseThrow());  // exact hit counts
        assertEquals("B", r.owner("k15").orElseThrow());  // 15 -> 20 (B)
        assertEquals("B", r.owner("k20").orElseThrow());
        assertEquals("B", r.owner("k45").orElseThrow());  // 45 -> 50 (B)
        assertEquals("A", r.owner("k55").orElseThrow());  // wraps to 10 (A)
    }

    @Test
    void preferenceListWalksClockwiseSkippingDuplicates() {
        HashRing r = fixture();
        assertEquals(List.of("A", "B"), r.preferenceList("k5", 2));
        assertEquals(List.of("B", "A"), r.preferenceList("k15", 5)); // only 2 nodes exist
        assertEquals(List.of("B"), r.preferenceList("k45", 1));
        assertEquals(List.of(), r.preferenceList("k45", 0));
    }

    @Test
    void shareFollowsArcLengthsIncludingWrap() {
        HashRing r = fixture();
        // arcs (prev, point]: 10: wrap from 50 -> 2^64-40 + ... computed below
        double total = Math.pow(2, 64);
        // A owns (50,10] wrapping = 2^64-40, plus (20,40] = 20  -> 2^64-20 ... in signed-shifted space
        // B owns (10,20] = 10 and (40,50] = 10 -> 20
        Map<String, Double> s = r.share();
        assertEquals(20.0 / total, s.get("B"), 1e-30);
        assertEquals(1.0, s.get("A") + s.get("B"), 1e-12);
        assertTrue(s.get("A") > 0.999999);
    }

    @Test
    void singleNodeOwnsEverything() {
        HashRing r = HashRing.empty().withNode("solo", 1);
        assertEquals(1.0, r.share().get("solo"), 1e-12); // 128 arcs summed in doubles
        assertEquals("solo", r.owner("anything").orElseThrow());
    }

    @Test
    void collidingPointsAreWonBySmallerIdRegardlessOfInsertionOrder() {
        Map<String, Long> m = new HashMap<>();
        m.put("Z#0", 7L); m.put("M#0", 7L); m.put("k", 3L);
        Hasher h = table(m);
        HashRing a = HashRing.empty(h, 1).withNode("Z", 1).withNode("M", 1);
        HashRing b = HashRing.empty(h, 1).withNode("M", 1).withNode("Z", 1);
        assertEquals("M", a.owner("k").orElseThrow());
        assertEquals("M", b.owner("k").orElseThrow());
        assertEquals(a, b);
        // Z lost its only point, so it is a member but unreachable on the ring.
        assertEquals(List.of("M"), a.preferenceList("k", 2));
        assertEquals(Set.of("M"), a.share().keySet());
        assertEquals(Set.of("M", "Z"), a.nodes().keySet());
    }

    @Test
    void withoutNodeRemovesOnlyThatNode() {
        HashRing r = fixture().withoutNode("A");
        assertEquals("B", r.owner("k55").orElseThrow());
        assertEquals(List.of("B"), r.preferenceList("k5", 3));
        assertEquals(fixture().withoutNode("nope"), fixture());
    }

    @Test
    void withNodeTwiceReplacesWeight() {
        HashRing r = HashRing.empty().withNode("a", 1).withNode("a", 3);
        assertEquals(HashRing.empty().withNode("a", 3), r);
        assertEquals(Set.of("a"), r.nodes().keySet());
        assertEquals(3, r.nodes().get("a"));
    }

    @Test
    void invalidArgumentsRejected() {
        HashRing r = HashRing.empty();
        assertThrows(IllegalArgumentException.class, () -> r.withNode("", 1));
        assertThrows(IllegalArgumentException.class, () -> r.withNode("a", 0));
        assertThrows(NullPointerException.class, () -> r.withNode(null, 1));
        assertThrows(IllegalArgumentException.class, () -> HashRing.empty(Hasher.DEFAULT, 0));
        assertThrows(IllegalArgumentException.class, () -> r.preferenceList("k", -1));
        assertThrows(NullPointerException.class, () -> r.owner(null));
    }

    @Test
    void insertionOrderDoesNotMatter() {
        HashRing a = HashRing.empty().withNode("n1", 1).withNode("n2", 2).withNode("n3", 1);
        HashRing b = HashRing.empty().withNode("n3", 1).withNode("n1", 1).withNode("n2", 2);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        for (int i = 0; i < 200; i++) {
            assertEquals(a.owner("key" + i), b.owner("key" + i));
        }
    }

    private static HashRing ring(int n) {
        HashRing r = HashRing.empty();
        for (int i = 0; i < n; i++) r = r.withNode("node" + i, 1);
        return r;
    }

    private static List<String> keys(int n) {
        List<String> ks = new ArrayList<>();
        for (int i = 0; i < n; i++) ks.add("user:" + i);
        return ks;
    }

    @Test
    void addingNodeOnlyMovesKeysToTheNewNode() {
        HashRing before = ring(10);
        HashRing after = before.withNode("fresh", 1);
        List<Rebalance.Move> moves = Rebalance.moves(before, after, keys(20000));
        assertFalse(moves.isEmpty());
        for (Rebalance.Move m : moves) assertEquals("fresh", m.to());
        double frac = moves.size() / 20000.0;
        assertEquals(1.0 / 11, frac, 0.03, "moved fraction " + frac);
    }

    @Test
    void removingNodeOnlyMovesItsOwnKeys() {
        HashRing before = ring(10);
        HashRing after = before.withoutNode("node3");
        List<String> ks = keys(20000);
        List<Rebalance.Move> moves = Rebalance.moves(before, after, ks);
        for (Rebalance.Move m : moves) assertEquals("node3", m.from());
        long owned = ks.stream().filter(k -> before.owner(k).orElseThrow().equals("node3")).count();
        assertEquals(owned, moves.size());
        assertNotEquals(0, owned);
    }

    @Test
    void sharesSumToOneAndAreBalancedWithManyVnodes() {
        HashRing r = HashRing.empty(Hasher.DEFAULT, 256);
        for (int i = 0; i < 8; i++) r = r.withNode("n" + i, 1);
        Map<String, Double> s = r.share();
        assertEquals(8, s.size());
        double sum = 0;
        for (double v : s.values()) {
            sum += v;
            assertEquals(0.125, v, 0.04, "share " + v);
        }
        assertEquals(1.0, sum, 1e-9);
    }

    @Test
    void weightIsProportionalToShare() {
        HashRing r = HashRing.empty(Hasher.DEFAULT, 400).withNode("small", 1).withNode("big", 3);
        Map<String, Double> s = r.share();
        assertEquals(0.25, s.get("small"), 0.05);
        assertEquals(0.75, s.get("big"), 0.05);
    }

    @Test
    void preferenceListHasDistinctNodesAndStartsWithOwner() {
        HashRing r = ring(7);
        for (String k : keys(500)) {
            List<String> p = r.preferenceList(k, 4);
            assertEquals(4, p.size());
            assertEquals(4, new HashSet<>(p).size(), "dupes in " + p);
            assertEquals(r.owner(k).orElseThrow(), p.get(0));
            assertEquals(p, r.preferenceList(k, 7).subList(0, 4));
        }
    }

    @Test
    void replicasSurviveRemovalOfOneReplica() {
        HashRing r = ring(9);
        for (String k : keys(300)) {
            List<String> p = r.preferenceList(k, 3);
            HashRing without = r.withoutNode(p.get(1));
            List<String> q = without.preferenceList(k, 3);
            assertEquals(p.get(0), q.get(0));
            assertEquals(p.get(2), q.get(1));
            assertFalse(q.contains(p.get(1)));
        }
    }

    @Test
    void boundedLoadRespectsCapacityAndAssignsEveryKey() {
        HashRing r = ring(5);
        List<String> ks = keys(1000);
        Map<String, String> a = r.assignBounded(ks, 1.0); // cap = 200 exactly
        assertEquals(1000, a.size());
        Map<String, Integer> load = new TreeMap<>();
        a.values().forEach(n -> load.merge(n, 1, Integer::sum));
        for (int l : load.values()) assertEquals(200, l, "loads " + load);
    }

    @Test
    void boundedLoadWithSlackStaysUnderCapAndPrefersOwner() {
        HashRing r = ring(6);
        List<String> ks = keys(1200);
        Map<String, String> a = r.assignBounded(ks, 1.25); // cap = ceil(1.25*200)=250
        Map<String, Integer> load = new TreeMap<>();
        a.values().forEach(n -> load.merge(n, 1, Integer::sum));
        for (int l : load.values()) assertTrue(l <= 250, "loads " + load);
        long same = ks.stream().filter(k -> a.get(k).equals(r.owner(k).orElseThrow())).count();
        assertTrue(same > 800, "kept owner for only " + same);
        // determinism
        assertEquals(a, r.assignBounded(ks, 1.25));
    }

    @Test
    void boundedLoadSpillsToNextNodeInPreferenceOrder() {
        HashRing r = fixture();
        // three keys all owned by A (5,10, 55); N=2, cap = ceil(1.0*3/2)=2
        Map<String, String> a = r.assignBounded(List.of("k5", "k10", "k55"), 1.0);
        assertEquals("A", a.get("k5"));
        assertEquals("A", a.get("k10"));
        assertEquals("B", a.get("k55")); // A is full, next clockwise distinct node is B
    }

    @Test
    void boundedLoadEdgeCases() {
        assertThrows(IllegalArgumentException.class, () -> ring(3).assignBounded(keys(3), 0.9));
        assertThrows(IllegalStateException.class, () -> HashRing.empty().assignBounded(List.of("k"), 1.0));
        assertEquals(Map.of(), HashRing.empty().assignBounded(List.of(), 1.0));
        assertThrows(IllegalArgumentException.class, () -> ring(2).assignBounded(List.of("a", "a"), 1.0));
    }
}
