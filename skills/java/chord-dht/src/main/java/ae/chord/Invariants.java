package ae.chord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Checks the live network against {@link RingOracle}; every method returns human-readable violations. */
public final class Invariants {
    private Invariants() {
    }

    public static List<String> all(ChordNetwork net) {
        List<String> out = ring(net);
        if (out.isEmpty()) {
            out.addAll(fingers(net));
        }
        if (out.isEmpty()) {
            out.addAll(storage(net));
        }
        return out;
    }

    /** Successor lists and predecessors equal the oracle's. */
    public static List<String> ring(ChordNetwork net) {
        List<String> out = new ArrayList<>();
        if (net.liveIds().isEmpty()) {
            return out;
        }
        RingOracle o = new RingOracle(net.ring(), net.liveIds());
        int n = net.liveIds().size();
        for (long id : net.liveIds()) {
            ChordNode node = net.node(id);
            List<Long> expectSucc = n == 1 ? List.of(id) : o.following(id, net.listSize());
            if (!node.successorList().equals(expectSucc)) {
                out.add("node " + id + " successor list " + node.successorList() + " expected " + expectSucc);
            }
            if (n > 1 && !node.predecessor().equals(java.util.Optional.of(o.predecessor(id)))) {
                out.add("node " + id + " predecessor " + node.predecessor() + " expected " + o.predecessor(id));
            }
        }
        return out;
    }

    /** Every finger is set and equals the ideal successor of its start point. */
    public static List<String> fingers(ChordNetwork net) {
        List<String> out = new ArrayList<>();
        if (net.liveIds().isEmpty()) {
            return out;
        }
        RingOracle o = new RingOracle(net.ring(), net.liveIds());
        for (long id : net.liveIds()) {
            List<Long> f = net.node(id).fingers();
            for (int i = 0; i < f.size(); i++) {
                long want = o.successor(net.ring().fingerStart(id, i));
                if (f.get(i) == null || f.get(i) != want) {
                    out.add("node " + id + " finger " + i + " = " + f.get(i) + " expected " + want);
                }
            }
        }
        return out;
    }

    /** Every stored key sits on exactly its replica set, with the same version everywhere. */
    public static List<String> storage(ChordNetwork net) {
        List<String> out = new ArrayList<>();
        if (net.liveIds().isEmpty()) {
            return out;
        }
        RingOracle o = new RingOracle(net.ring(), net.liveIds());
        Set<Long> keys = new TreeSet<>();
        for (long id : net.liveIds()) {
            keys.addAll(net.node(id).storeKeys());
        }
        for (long key : keys) {
            Set<Long> holders = new HashSet<>();
            Set<Long> versions = new HashSet<>();
            for (long id : net.liveIds()) {
                var v = net.node(id).stored(key);
                if (v.isPresent()) {
                    holders.add(id);
                    versions.add(v.get().version());
                }
            }
            Set<Long> want = new HashSet<>(o.replicas(key, net.listSize()));
            if (!holders.equals(want)) {
                out.add("key " + key + " held by " + new TreeSet<>(holders) + " expected " + new TreeSet<>(want));
            }
            if (versions.size() > 1) {
                out.add("key " + key + " has divergent versions " + versions);
            }
        }
        return out;
    }
}
