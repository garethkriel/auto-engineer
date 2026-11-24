package ae.chord;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;

/** The simulated network: owns the live nodes, counts messages and drives rounds of maintenance. */
public final class ChordNetwork {
    private final Ring ring;
    private final int listSize;
    private final TreeMap<Long, ChordNode> live = new TreeMap<>();
    private long messages;
    private long clock;

    /** @param listSize successor-list length r, also the number of copies kept of each key */
    public ChordNetwork(Ring ring, int listSize) {
        if (listSize < 1) {
            throw new IllegalArgumentException("listSize must be >= 1");
        }
        this.ring = ring;
        this.listSize = listSize;
    }

    public Ring ring() {
        return ring;
    }

    public int listSize() {
        return listSize;
    }

    public long messages() {
        return messages;
    }

    int liveCount() {
        return live.size();
    }

    public NavigableSet<Long> liveIds() {
        return new java.util.TreeSet<>(live.keySet());
    }

    /** Direct (unmetered) access for tests and checkers. */
    public ChordNode node(long id) {
        ChordNode n = live.get(id);
        if (n == null) {
            throw new NodeDownException(id);
        }
        return n;
    }

    // ------------------------------------------------------------------ RPC primitives

    boolean ping(long id) {
        messages++;
        return live.containsKey(id);
    }

    ChordNode call(long id) {
        messages++;
        return node(id);
    }

    // ------------------------------------------------------------------ membership

    public ChordNode create(long id) {
        if (!live.isEmpty()) {
            throw new IllegalArgumentException("network already has nodes; use join");
        }
        checkId(id);
        ChordNode n = new ChordNode(id, this, listSize);
        for (int i = 0; i < ring.bits(); i++) {
            n.fingersInitTo(id, i);
        }
        live.put(id, n);
        return n;
    }

    public ChordNode join(long id, long via) {
        checkId(id);
        if (live.containsKey(id)) {
            throw new IllegalArgumentException("id already in use: " + id);
        }
        ChordNode v = live.get(via);
        if (v == null) {
            throw new IllegalArgumentException("bootstrap node is not alive: " + via);
        }
        ChordNode n = new ChordNode(id, this, listSize);
        n.joinVia(v);
        live.put(id, n);
        return n;
    }

    /** Silent failure: the node vanishes with its data. */
    public void crash(long id) {
        if (live.remove(id) == null) {
            throw new IllegalArgumentException("no such live node: " + id);
        }
    }

    /** Graceful departure: data and neighbour pointers are handed over first. */
    public void leave(long id) {
        ChordNode n = live.get(id);
        if (n == null) {
            throw new IllegalArgumentException("no such live node: " + id);
        }
        if (live.size() > 1) {
            n.handOver();
        }
        live.remove(id);
    }

    private void checkId(long id) {
        if (id < 0 || id >= ring.size()) {
            throw new IllegalArgumentException("id outside the ring: " + id);
        }
    }

    // ------------------------------------------------------------------ maintenance rounds

    /** One maintenance cycle on every live node (in id order, on a snapshot). */
    public void round() {
        for (long id : new ArrayList<>(live.keySet())) {
            ChordNode n = live.get(id);
            if (n == null) {
                continue;
            }
            n.checkPredecessor();
            n.stabilize();
            n.fixNextFinger();
            n.maintain();
        }
    }

    /**
     * Runs rounds until {@link Invariants#all} is clean.
     *
     * @return the number of rounds that were needed, or -1 if {@code maxRounds} was not enough
     */
    public int stabilize(int maxRounds) {
        for (int i = 0; ; i++) {
            if (Invariants.all(this).isEmpty()) {
                return i;
            }
            if (i == maxRounds) {
                return -1;
            }
            round();
        }
    }

    // ------------------------------------------------------------------ client operations

    public Lookup lookup(long start, long key) {
        return node(start).findSuccessor(key);
    }

    /** Writes to the owner and its next r-1 live successors; returns the number of copies written. */
    public int put(long start, long key, String value) {
        long k = ring.mod(key);
        Versioned v = new Versioned(++clock, value);
        ChordNode owner = node(lookup(start, k).owner());
        owner.storeLocal(k, v);
        int copies = 1;
        for (long s : owner.successorList()) {
            if (copies >= listSize) {
                break;
            }
            if (s != owner.id() && ping(s)) {
                node(s).storeLocal(k, v);
                copies++;
            }
        }
        return copies;
    }

    public int put(long start, String key, String value) {
        return put(start, ring.hash(key), value);
    }

    /** Reads the owner and its replicas and returns the newest version found. */
    public Optional<String> get(long start, long key) {
        long k = ring.mod(key);
        ChordNode owner = node(lookup(start, k).owner());
        Versioned best = owner.stored(k).orElse(null);
        int seen = 1;
        for (long s : owner.successorList()) {
            if (seen >= listSize) {
                break;
            }
            if (s != owner.id() && ping(s)) {
                seen++;
                Versioned v = node(s).stored(k).orElse(null);
                if (v != null && (best == null || v.version() > best.version())) {
                    best = v;
                }
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.value());
    }

    public Optional<String> get(long start, String key) {
        return get(start, ring.hash(key));
    }

    /** Live nodes as an ordered list (convenience for tests). */
    public List<Long> liveList() {
        return new ArrayList<>(live.keySet());
    }
}
