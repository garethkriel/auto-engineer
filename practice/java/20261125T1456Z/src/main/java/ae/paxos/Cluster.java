package ae.paxos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/** Deterministic in-process network of {@link Node}s with seeded loss, duplication, delay, partitions and crashes. */
public final class Cluster {
    private record InFlight(long at, long seq, Msg msg) { }

    private final int n;
    private final Node[] nodes;
    private final boolean[] crashed;
    private final Random rnd;
    private double drop;
    private double dup;
    private final int maxDelay;
    private final Set<Long> blocked = new HashSet<>();
    private final PriorityQueue<InFlight> queue = new PriorityQueue<>(
            (a, b) -> a.at() != b.at() ? Long.compare(a.at(), b.at()) : Long.compare(a.seq(), b.seq()));
    private long seq;
    private long now;
    private final Set<String> submitted = new HashSet<>();
    private final Map<Integer, String> chosen = new HashMap<>();
    private final Ballot[] lastPromised;

    public Cluster(int n, long seed, double drop, double dup, int maxDelay) {
        if (maxDelay < 1) throw new IllegalArgumentException("maxDelay");
        this.n = n;
        this.nodes = new Node[n];
        this.crashed = new boolean[n];
        this.lastPromised = new Ballot[n];
        for (int i = 0; i < n; i++) {
            nodes[i] = new Node(i, n);
            lastPromised[i] = Ballot.NONE;
        }
        this.rnd = new Random(seed);
        this.drop = drop;
        this.dup = dup;
        this.maxDelay = maxDelay;
    }

    public int size() { return n; }

    public Node node(int i) { return nodes[i]; }

    public boolean isCrashed(int i) { return crashed[i]; }

    public long now() { return now; }

    public void submit(int node, String cmd) {
        submitted.add(cmd);
        if (!crashed[node]) nodes[node].submit(cmd, now);
    }

    public void setLossless() { drop = 0; dup = 0; }

    // ---- faults ----
    private static long key(int a, int b) { return (long) Math.min(a, b) * 1000 + Math.max(a, b); }

    public void block(int a, int b) { blocked.add(key(a, b)); }

    public void isolate(int a) { for (int b = 0; b < n; b++) if (b != a) block(a, b); }

    public void heal() { blocked.clear(); }

    public void crash(int i) {
        if (crashed[i]) throw new IllegalStateException("already crashed");
        crashed[i] = true;
        nodes[i].crash();
    }

    public void restart(int i) {
        if (!crashed[i]) throw new IllegalStateException("not crashed");
        crashed[i] = false;
    }

    // ---- simulation ----
    private void transmit(Msg m) {
        if (blocked.contains(key(m.from(), m.to()))) return;
        if (rnd.nextDouble() < drop) return;
        int copies = rnd.nextDouble() < dup ? 2 : 1;
        for (int c = 0; c < copies; c++) {
            queue.add(new InFlight(now + 1 + rnd.nextInt(maxDelay), seq++, m));
        }
    }

    /** Advances one tick: every live node ticks, then due messages are delivered. */
    public void step() {
        now++;
        for (int i = 0; i < n; i++) {
            if (crashed[i]) continue;
            nodes[i].tick(now);
            for (Msg m : nodes[i].drainOutbox()) transmit(m);
        }
        while (!queue.isEmpty() && queue.peek().at() <= now) {
            Msg m = queue.poll().msg();
            if (crashed[m.to()] || blocked.contains(key(m.from(), m.to()))) continue;
            nodes[m.to()].receive(m, now);
            for (Msg o : nodes[m.to()].drainOutbox()) transmit(o);
        }
    }

    /** Throws {@link IllegalStateException} naming the violated rule. */
    public void checkSafety() {
        for (int i = 0; i < n; i++) {
            Node nd = nodes[i];
            if (nd.promised().compareTo(lastPromised[i]) < 0) {
                throw new IllegalStateException("promise regressed at node " + i + ": " + lastPromised[i] + " -> " + nd.promised());
            }
            lastPromised[i] = nd.promised();
            for (Map.Entry<Integer, String> e : nd.decided().entrySet()) {
                String prev = chosen.putIfAbsent(e.getKey(), e.getValue());
                if (prev != null && !prev.equals(e.getValue())) {
                    throw new IllegalStateException("agreement violated at slot " + e.getKey() + ": " + prev + " vs " + e.getValue()
                            + " (node " + i + ")");
                }
                if (!e.getValue().equals(Node.NOOP) && !submitted.contains(e.getValue())) {
                    throw new IllegalStateException("validity violated at slot " + e.getKey() + ": " + e.getValue());
                }
            }
        }
    }

    /** Decided slots across all nodes (for tests). */
    public List<Integer> decidedSlots() {
        List<Integer> out = new ArrayList<>(chosen.keySet());
        out.sort(null);
        return out;
    }

    // ---- test hooks ----
    void corruptDecided(int node, int slot, String value) { nodes[node].forceDecided(slot, value); }

    void corruptPromised(int node, Ballot b) { nodes[node].forcePromised(b); }
}
