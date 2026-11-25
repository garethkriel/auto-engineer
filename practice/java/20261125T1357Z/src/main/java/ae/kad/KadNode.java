package ae.kad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** A Kademlia node: routing table, local store, and iterative lookup / put / get. */
public final class KadNode {
    private enum State { NEW, QUERIED, FAILED }

    private final NodeId id;
    private final Network net;
    private final int k;
    private final int alpha;
    private final RoutingTable table;
    private final Map<NodeId, String> store = new HashMap<>();
    /** Contacts that were the oldest of a full bucket when a newcomer arrived; pinged after operations. */
    private final Set<NodeId> suspects = new LinkedHashSet<>();

    public KadNode(NodeId id, Network net, int k, int alpha) {
        this.id = Objects.requireNonNull(id);
        this.net = Objects.requireNonNull(net);
        if (k < 1 || alpha < 1) throw new IllegalArgumentException("k and alpha must be >= 1");
        this.k = k;
        this.alpha = alpha;
        this.table = new RoutingTable(id, k);
        net.register(this);
    }

    public NodeId id() {
        return id;
    }

    public RoutingTable table() {
        return table;
    }

    public synchronized Optional<String> localValue(NodeId key) {
        return Optional.ofNullable(store.get(key));
    }

    // ---- RPC handlers (called by Network). They never issue RPCs themselves. ----

    boolean onPing(NodeId from) {
        heardFrom(from);
        return true;
    }

    List<NodeId> onFindNode(NodeId from, NodeId target) {
        heardFrom(from);
        return table.closest(target, k);
    }

    Network.ValueReply onFindValue(NodeId from, NodeId key) {
        heardFrom(from);
        Optional<String> v = localValue(key);
        return new Network.ValueReply(v, v.isPresent() ? List.of() : table.closest(key, k));
    }

    boolean onStore(NodeId from, NodeId key, String value) {
        heardFrom(from);
        synchronized (this) {
            store.put(key, Objects.requireNonNull(value));
        }
        return true;
    }

    private synchronized void heardFrom(NodeId other) {
        table.touch(other).ifPresent(suspects::add);
    }

    // ---- client operations ----

    /** Joins the network through a known contact by looking up our own id. */
    public void join(NodeId contact) {
        if (contact.equals(id)) throw new IllegalArgumentException("cannot join through self");
        heardFrom(contact);
        lookup(id);
        refresh();
    }

    /**
     * Bucket refresh: for every bucket index from our nearest known neighbour's bucket upwards, look up a
     * pseudo-random id that falls in that bucket's range, so far-away regions of the id space get contacts.
     */
    public void refresh() {
        List<NodeId> near = table.closest(id, 1);
        if (near.isEmpty()) return;
        int first = id.bucketIndex(near.get(0));
        Random rnd = new Random(id.value());
        for (int i = first; i < 64; i++) {
            long low = i == 0 ? 0 : rnd.nextLong() & ((1L << i) - 1); // random bits below bit i
            lookup(new NodeId(id.value() ^ (1L << i) ^ low));
        }
    }

    /** Iterative node lookup: the (up to) k closest live nodes to target, excluding self, nearest first. */
    public List<NodeId> lookup(NodeId target) {
        List<NodeId> result = iterate(target, false).nearest;
        pingSuspects();
        return result;
    }

    /** Stores the value on the k closest nodes to key (self included when it is among them). */
    public void put(NodeId key, String value) {
        Objects.requireNonNull(value);
        List<NodeId> holders = new ArrayList<>(iterate(key, false).nearest);
        holders.add(id);
        holders.sort(NodeId.closerTo(key));
        for (NodeId h : holders.subList(0, Math.min(k, holders.size()))) {
            if (h.equals(id)) {
                onStore(id, key, value);
            } else if (net.store(id, h, key, value)) {
                heardFrom(h);
            } else {
                table.evict(h);
            }
        }
        pingSuspects();
    }

    /** Finds a value: local copy first, otherwise an iterative findValue that stops at the first hit. */
    public Optional<String> get(NodeId key) {
        Optional<String> local = localValue(key);
        if (local.isPresent()) return local;
        Optional<String> found = iterate(key, true).value;
        pingSuspects();
        return found;
    }

    private record Outcome(List<NodeId> nearest, Optional<String> value) {}

    private Outcome iterate(NodeId target, boolean wantValue) {
        Map<NodeId, State> state = new HashMap<>();
        Set<NodeId> failedOnce = new java.util.HashSet<>();
        List<NodeId> shortlist = new ArrayList<>();
        for (NodeId c : table.closest(target, k)) {
            state.put(c, State.NEW);
            shortlist.add(c);
        }
        while (true) {
            shortlist.sort(NodeId.closerTo(target));
            List<NodeId> batch = new ArrayList<>();
            int live = 0;
            for (NodeId c : shortlist) {
                State s = state.get(c);
                if (s == State.FAILED) continue;
                if (live++ == k) break;
                if (s == State.NEW && batch.size() < alpha) batch.add(c);
            }
            if (batch.isEmpty()) break;
            for (NodeId c : batch) {
                Optional<List<NodeId>> reply;
                if (wantValue) {
                    Optional<Network.ValueReply> r = net.findValue(id, c, target);
                    if (r.isPresent() && r.get().value().isPresent()) {
                        heardFrom(c);
                        return new Outcome(List.of(), r.get().value());
                    }
                    reply = r.map(Network.ValueReply::nodes);
                } else {
                    reply = net.findNode(id, c, target);
                }
                if (reply.isEmpty()) {
                    // one retry (the contact stays NEW) before it is declared dead and evicted
                    if (failedOnce.add(c)) continue;
                    state.put(c, State.FAILED);
                    table.evict(c);
                    continue;
                }
                state.put(c, State.QUERIED);
                heardFrom(c);
                for (NodeId n : reply.get()) {
                    if (!n.equals(id) && state.putIfAbsent(n, State.NEW) == null) shortlist.add(n);
                }
            }
        }
        List<NodeId> nearest = new ArrayList<>();
        for (NodeId c : shortlist) {
            if (state.get(c) != State.FAILED && nearest.size() < k) nearest.add(c);
        }
        return new Outcome(nearest, Optional.empty());
    }

    /** Pings recorded stale candidates: dead ones are evicted (a replacement is promoted), live ones kept. */
    private void pingSuspects() {
        List<NodeId> pending;
        synchronized (this) {
            pending = new ArrayList<>(suspects);
            suspects.clear();
        }
        for (NodeId s : pending) {
            if (!table.contains(s)) continue;
            if (net.ping(id, s)) heardFrom(s);
            else table.evict(s);
        }
    }
}
