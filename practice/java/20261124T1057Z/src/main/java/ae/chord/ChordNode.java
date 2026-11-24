package ae.chord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One Chord node. All remote interaction goes through the owning {@link ChordNetwork}, which counts
 * messages and refuses calls to dead nodes. Single-threaded by design (the network drives it).
 */
public final class ChordNode {
    private final long id;
    private final ChordNetwork net;
    private final Ring ring;
    private final int listSize;

    private Long pred;
    private final ArrayList<Long> succ = new ArrayList<>();
    private final Long[] fingers;
    private int nextFinger;
    private final TreeMap<Long, Versioned> store = new TreeMap<>();

    ChordNode(long id, ChordNetwork net, int listSize) {
        this.id = id;
        this.net = net;
        this.ring = net.ring();
        this.listSize = listSize;
        this.fingers = new Long[ring.bits()];
        this.succ.add(id);
    }

    public long id() {
        return id;
    }

    public Optional<Long> predecessor() {
        return Optional.ofNullable(pred);
    }

    /** Copy of the successor list (first entry is the successor). */
    public List<Long> successorList() {
        return List.copyOf(succ);
    }

    /** Copy of the finger table; unset entries are {@code null}. */
    public List<Long> fingers() {
        return Arrays.asList(fingers.clone());
    }

    public NavigableSet<Long> storeKeys() {
        return new java.util.TreeSet<>(store.keySet());
    }

    /** Used by {@link ChordNetwork#create}: a lone node's fingers all point at itself. */
    void fingersInitTo(long target, int i) {
        fingers[i] = target;
    }

    public Optional<Versioned> stored(long key) {
        return Optional.ofNullable(store.get(key));
    }

    // ------------------------------------------------------------------ routing

    /** First live entry of the successor list; dead heads are dropped. Falls back to self when none is alive. */
    long successor() {
        while (!succ.isEmpty()) {
            long s = succ.get(0);
            if (s == id || net.ping(s)) {
                return s;
            }
            succ.remove(0);
        }
        succ.add(id);
        return id;
    }

    /** Highest known live node strictly between this node and {@code key}; this node if none. */
    long closestPreceding(long key) {
        for (int i = fingers.length - 1; i >= 0; i--) {
            Long f = fingers[i];
            if (f != null && ring.inOpen(f, id, key)) {
                if (net.ping(f)) {
                    return f;
                }
                fingers[i] = null;
            }
        }
        for (int i = succ.size() - 1; i >= 0; i--) {
            long s = succ.get(i);
            if (s != id && ring.inOpen(s, id, key) && net.ping(s)) {
                return s;
            }
        }
        return id;
    }

    /** Iterative lookup starting here: hop to the closest preceding node until the key falls before a successor. */
    Lookup findSuccessor(long key) {
        long target = ring.mod(key);
        long cur = id;
        List<Long> path = new ArrayList<>();
        path.add(cur);
        int budget = net.liveCount() + 2 * ring.bits() + 8;
        int hops = 0;
        while (true) {
            ChordNode c = net.call(cur);
            long s = c.successor();
            if (ring.inHalfOpen(target, cur, s)) {
                return new Lookup(s, hops, path);
            }
            long next = c.closestPreceding(target);
            if (next == cur) {
                next = s; // no useful finger: walk the ring
            }
            cur = next;
            path.add(cur);
            hops++;
            if (hops > budget) {
                throw new LookupFailedException("lookup of " + key + " from " + id + " exceeded " + budget + " hops");
            }
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** Called when this node joins: learn the successor and take over the keys it was holding for us. */
    void joinVia(ChordNode via) {
        pred = null;
        succ.clear();
        long s = via.findSuccessor(id).owner();
        succ.add(s);
        ChordNode sn = net.call(s);
        Long p = sn.pred;
        if (p != null && net.ping(p) && p != id) {
            for (Map.Entry<Long, Versioned> e : sn.store.entrySet()) {
                if (ring.inHalfOpen(e.getKey(), p, id)) {
                    storeLocal(e.getKey(), e.getValue());
                }
            }
        }
    }

    /** Verify the successor, adopt a closer one if it appeared, notify it and refresh the successor list. */
    void stabilize() {
        long s = successor();
        Long x = (s == id) ? pred : net.call(s).pred;
        if (x != null && x != id && net.ping(x) && ring.inOpen(x, id, s)) {
            s = x;
        } else if (s == id && x != null && x != id && net.ping(x)) {
            s = x;
        }
        if (s != id) {
            ChordNode sn = net.call(s);
            sn.notifyPredecessor(id);
            ArrayList<Long> fresh = new ArrayList<>();
            fresh.add(s);
            for (long t : sn.succ) {
                if (t == id) {
                    break;
                }
                if (!fresh.contains(t) && fresh.size() < listSize) {
                    fresh.add(t);
                }
            }
            succ.clear();
            succ.addAll(fresh);
        } else {
            succ.clear();
            succ.add(id);
        }
    }

    /** {@code candidate} believes it is our predecessor. */
    void notifyPredecessor(long candidate) {
        if (candidate == id) {
            return;
        }
        if (pred == null || !net.ping(pred) || ring.inOpen(candidate, pred, id)) {
            pred = candidate;
        }
    }

    void checkPredecessor() {
        if (pred != null && !net.ping(pred)) {
            pred = null;
        }
    }

    /** Refresh one finger per call, round-robin. */
    void fixNextFinger() {
        int i = nextFinger;
        nextFinger = (nextFinger + 1) % fingers.length;
        fingers[i] = findSuccessor(ring.fingerStart(id, i)).owner();
    }

    /**
     * Hand each stored key to its owner, replicate owned keys to the successor list and drop copies this node
     * should no longer hold (it is outside the owner's replica set).
     */
    void maintain() {
        for (Map.Entry<Long, Versioned> e : new ArrayList<>(store.entrySet())) {
            long key = e.getKey();
            Versioned v = e.getValue();
            long owner = findSuccessor(key).owner();
            if (owner == id) {
                int copies = 1;
                for (long s : new ArrayList<>(succ)) {
                    if (copies >= listSize) {
                        break;
                    }
                    if (s != id && net.ping(s)) {
                        net.call(s).storeLocal(key, v);
                        copies++;
                    }
                }
            } else {
                ChordNode on = net.call(owner);
                on.storeLocal(key, v);
                if (!on.replicaSet().contains(id)) {
                    store.remove(key);
                }
            }
        }
    }

    /** This node followed by its first live successors: the nodes that hold copies of keys it owns. */
    List<Long> replicaSet() {
        List<Long> out = new ArrayList<>();
        out.add(id);
        for (long s : succ) {
            if (out.size() >= listSize) {
                break;
            }
            if (s != id && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ storage and graceful leave

    /** Keeps the version with the larger write clock. */
    void storeLocal(long key, Versioned v) {
        Versioned old = store.get(key);
        if (old == null || old.version() < v.version()) {
            store.put(key, v);
        }
    }

    /** Graceful departure: give every key to the successor and splice the neighbours together. */
    void handOver() {
        long s = successor();
        if (s != id) {
            ChordNode sn = net.call(s);
            for (Map.Entry<Long, Versioned> e : store.entrySet()) {
                sn.storeLocal(e.getKey(), e.getValue());
            }
            sn.pred = (pred != null && net.ping(pred) && pred != s) ? pred : null;
        }
        if (pred != null && net.ping(pred)) {
            ChordNode pn = net.call(pred);
            pn.succ.remove(Long.valueOf(id));
            if (pn.succ.isEmpty()) {
                pn.succ.add(s != id ? s : pn.id);
            }
        }
    }
}
