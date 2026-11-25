package ae.kad;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DhtTest {
    static final int K = 8;
    static final int ALPHA = 3;

    /** Builds n nodes joined one by one through node 0 (the bootstrap). */
    static List<KadNode> build(Network net, int n, long seed) {
        Random r = new Random(seed);
        List<KadNode> nodes = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        while (nodes.size() < n) {
            long v = r.nextLong();
            if (!used.add(v)) continue;
            KadNode node = new KadNode(new NodeId(v), net, K, ALPHA);
            if (!nodes.isEmpty()) node.join(nodes.get(0).id());
            nodes.add(node);
        }
        return nodes;
    }

    static List<NodeId> oracle(Collection<KadNode> nodes, NodeId target, NodeId exclude, Network net, int count) {
        List<NodeId> ids = new ArrayList<>();
        for (KadNode k : nodes) if (!k.id().equals(exclude) && net.isUp(k.id())) ids.add(k.id());
        ids.sort(NodeId.closerTo(target));
        return ids.subList(0, Math.min(count, ids.size()));
    }

    @Test
    void twoNodesFindEachOther() {
        Network net = new Network();
        KadNode a = new KadNode(new NodeId(1), net, K, ALPHA);
        KadNode b = new KadNode(new NodeId(2), net, K, ALPHA);
        b.join(a.id());
        assertTrue(a.table().contains(b.id()), "handler must touch the sender");
        assertTrue(b.table().contains(a.id()));
        assertEquals(List.of(b.id()), a.lookup(new NodeId(100)));
        assertEquals(List.of(a.id()), b.lookup(new NodeId(100)));
    }

    @Test
    void lookupReturnsExactKClosestInConnectedNetwork() {
        Network net = new Network();
        List<KadNode> nodes = build(net, 150, 11);
        Random r = new Random(5);
        for (int i = 0; i < 60; i++) {
            KadNode from = nodes.get(r.nextInt(nodes.size()));
            NodeId target = new NodeId(r.nextLong());
            List<NodeId> got = from.lookup(target);
            assertEquals(oracle(nodes, target, from.id(), net, K), got, "lookup #" + i);
        }
    }

    @Test
    void lookupOfAnExistingNodeIdFindsItFirst() {
        Network net = new Network();
        List<KadNode> nodes = build(net, 100, 2);
        KadNode from = nodes.get(7), want = nodes.get(63);
        assertEquals(want.id(), from.lookup(want.id()).get(0));
    }

    @Test
    void lookupRpcCountIsFarBelowNetworkSize() {
        Network net = new Network();
        List<KadNode> nodes = build(net, 150, 11);
        Random r = new Random(9);
        int worst = 0;
        for (int i = 0; i < 40; i++) {
            net.resetRpcCount();
            nodes.get(r.nextInt(nodes.size())).lookup(new NodeId(r.nextLong()));
            worst = Math.max(worst, net.rpcCount());
        }
        assertTrue(worst >= K, "must query at least the k closest, got " + worst);
        assertTrue(worst <= 60, "lookup should be O(k log n), got " + worst);
    }

    @Test
    void lookupSurvivesDeadNodesAndEvictsThem() {
        Network net = new Network();
        List<KadNode> nodes = build(net, 150, 11);
        Random r = new Random(21);
        Set<NodeId> dead = new HashSet<>();
        for (int i = 1; i < nodes.size(); i++) {
            if (r.nextInt(4) == 0) {
                dead.add(nodes.get(i).id());
                net.setUp(nodes.get(i).id(), false);
            }
        }
        assertTrue(dead.size() > 20);
        // repair phase: every survivor refreshes its buckets once, evicting the dead and finding replacements
        for (KadNode n : nodes) if (net.isUp(n.id())) n.refresh();
        KadNode from = nodes.get(0);
        for (int i = 0; i < 40; i++) {
            NodeId target = new NodeId(r.nextLong());
            List<NodeId> got = from.lookup(target);
            assertEquals(oracle(nodes, target, from.id(), net, K), got, "lookup #" + i);
            for (NodeId id : got) assertFalse(dead.contains(id));
        }
        // every dead contact a lookup queried and found dead was evicted from the querier's table
        for (NodeId d : dead) {
            if (from.table().contains(d)) {
                // only allowed if no lookup ever queried it; verify by querying it directly
                assertFalse(net.ping(from.id(), d));
            }
        }
        // after sweeping lookups toward each dead id, none remains known
        for (NodeId d : dead) from.lookup(d);
        for (NodeId d : dead) assertFalse(from.table().contains(d), "dead node still in table");
    }

    @Test
    void lookupToleratesDroppedRpcs() {
        Network net = new Network();
        List<KadNode> nodes = build(net, 120, 4);
        // drop every 5th RPC deterministically
        int[] counter = {0};
        net.setFault((from, to) -> ++counter[0] % 5 == 0);
        Random r = new Random(77);
        int exact = 0;
        for (int i = 0; i < 30; i++) {
            KadNode from = nodes.get(r.nextInt(nodes.size()));
            NodeId target = new NodeId(r.nextLong());
            List<NodeId> got = from.lookup(target);
            List<NodeId> want = oracle(nodes, target, from.id(), net, K);
            assertEquals(want.size(), got.size(), "must still return k results");
            Set<NodeId> overlap = new HashSet<>(got);
            overlap.retainAll(want);
            assertTrue(overlap.size() >= K - 2, "recall too low: " + overlap.size());
            if (got.equals(want)) exact++;
        }
        assertTrue(exact >= 10, "dropped RPCs should not destroy most lookups, exact=" + exact);
    }

    @Test
    void putStoresOnKClosestIncludingSelfWhenCloser() {
        Network net = new Network();
        List<KadNode> nodes = build(net, 100, 8);
        NodeId key = new NodeId(0x1234_5678_9ABC_DEF0L);
        KadNode writer = nodes.get(30);
        writer.put(key, "hello");
        List<NodeId> holders = new ArrayList<>();
        for (KadNode k : nodes) if (k.localValue(key).isPresent()) holders.add(k.id());
        holders.sort(NodeId.closerTo(key));
        List<NodeId> allSorted = new ArrayList<>();
        for (KadNode k : nodes) allSorted.add(k.id());
        allSorted.sort(NodeId.closerTo(key));
        assertEquals(allSorted.subList(0, K), holders);
    }

    @Test
    void getFindsValueFromAnyNodeAndMissingKeyIsEmpty() {
        Network net = new Network();
        List<KadNode> nodes = build(net, 100, 8);
        Random r = new Random(1);
        Map<NodeId, String> written = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            NodeId key = new NodeId(r.nextLong());
            written.put(key, "v" + i);
            nodes.get(r.nextInt(nodes.size())).put(key, "v" + i);
        }
        for (Map.Entry<NodeId, String> e : written.entrySet()) {
            for (int j = 0; j < 5; j++) {
                KadNode reader = nodes.get(r.nextInt(nodes.size()));
                assertEquals(Optional.of(e.getValue()), reader.get(e.getKey()));
            }
        }
        assertEquals(Optional.empty(), nodes.get(3).get(new NodeId(r.nextLong())));
    }

    @Test
    void getSurvivesLossOfReplicasButNotAllOfThem() {
        Network net = new Network();
        List<KadNode> nodes = build(net, 100, 8);
        NodeId key = new NodeId(42);
        nodes.get(5).put(key, "x");
        List<KadNode> holders = new ArrayList<>();
        for (KadNode k : nodes) if (k.localValue(key).isPresent()) holders.add(k);
        assertEquals(K, holders.size());
        holders.sort((a, b) -> NodeId.closerTo(key).compare(a.id(), b.id()));
        KadNode reader = nodes.stream().filter(k -> holders.stream().noneMatch(h -> h == k)).findFirst().orElseThrow();
        for (int i = 0; i < K - 1; i++) net.setUp(holders.get(i).id(), false);
        assertEquals(Optional.of("x"), reader.get(key));
        net.setUp(holders.get(K - 1).id(), false);
        assertEquals(Optional.empty(), reader.get(key));
    }

    @Test
    void overwriteReplacesValueEverywhere() {
        Network net = new Network();
        List<KadNode> nodes = build(net, 60, 13);
        NodeId key = new NodeId(-77L);
        nodes.get(1).put(key, "old");
        nodes.get(2).put(key, "new");
        for (KadNode k : nodes) k.localValue(key).ifPresent(v -> assertEquals("new", v));
        assertEquals(Optional.of("new"), nodes.get(40).get(key));
    }

    @Test
    void staleSuspectIsEvictedWhileLiveOldContactIsKept() {
        // k=1 so every second contact in a bucket creates a suspect.
        Network net = new Network();
        KadNode self = new KadNode(new NodeId(0), net, 1, 1);
        KadNode live = new KadNode(new NodeId(8), net, 1, 1);
        KadNode newcomer = new KadNode(new NodeId(9), net, 1, 1);
        live.join(self.id());
        assertEquals(List.of(live.id()), self.table().bucket(3));
        // newcomer contacts self; bucket 3 is full -> suspect = live; live is up, so it stays
        newcomer.join(self.id());
        assertEquals(List.of(live.id()), self.table().bucket(3));
        assertEquals(List.of(newcomer.id()), self.table().replacements(3));
        // now live dies; next time newcomer talks to self the stale one is evicted and newcomer promoted
        net.setUp(live.id(), false);
        newcomer.lookup(new NodeId(1000)); // newcomer queries self -> self touches newcomer -> suspect check
        self.lookup(new NodeId(1000)); // self runs its suspect pings at end of operation
        assertEquals(List.of(newcomer.id()), self.table().bucket(3));
    }

    @Test
    void deadSuspectIsEvictedByTheEndOfAnOperationThatNeverQueriedIt() {
        Network net = new Network();
        KadNode self = new KadNode(new NodeId(0), net, 1, 1);
        KadNode one = new KadNode(new NodeId(1), net, 1, 1);
        KadNode eight = new KadNode(new NodeId(8), net, 1, 1);
        KadNode nine = new KadNode(new NodeId(9), net, 1, 1);
        one.join(self.id());
        eight.join(self.id());
        nine.join(self.id());
        assertEquals(List.of(eight.id()), self.table().bucket(3));
        assertEquals(List.of(nine.id()), self.table().replacements(3));
        net.setUp(eight.id(), false);
        // lookup(1) queries only node 1 (k=1), so 8 is touched solely by the suspect check
        self.lookup(new NodeId(1));
        assertEquals(List.of(nine.id()), self.table().bucket(3));
        assertEquals(List.of(), self.table().replacements(3));
    }

    @Test
    void rpcPrimitivesFailOnDownOrUnknownTargets() {
        Network net = new Network();
        KadNode a = new KadNode(new NodeId(1), net, K, ALPHA);
        KadNode b = new KadNode(new NodeId(2), net, K, ALPHA);
        assertTrue(net.ping(a.id(), b.id()));
        assertFalse(net.ping(a.id(), new NodeId(99)));
        net.setUp(b.id(), false);
        assertFalse(net.ping(a.id(), b.id()));
        assertTrue(net.findNode(a.id(), b.id(), new NodeId(5)).isEmpty());
        assertFalse(net.store(a.id(), b.id(), new NodeId(5), "x"));
        assertTrue(net.findValue(a.id(), b.id(), new NodeId(5)).isEmpty());
        // raw Network calls do not make the caller learn the callee; the callee did learn the caller
        assertFalse(a.table().contains(b.id()));
        assertTrue(b.table().contains(a.id()));
    }

    @Test
    void isolatedNodeLookupIsEmptyAndPutStoresLocally() {
        Network net = new Network();
        KadNode a = new KadNode(new NodeId(1), net, K, ALPHA);
        assertEquals(List.of(), a.lookup(new NodeId(77)));
        a.put(new NodeId(5), "solo");
        assertEquals(Optional.of("solo"), a.localValue(new NodeId(5)));
        assertEquals(Optional.of("solo"), a.get(new NodeId(5)));
    }
}
