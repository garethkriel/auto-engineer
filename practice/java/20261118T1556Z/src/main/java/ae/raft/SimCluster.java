package ae.raft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic in-process simulation of a Raft cluster on an unreliable network.
 *
 * <p>Everything (node timeouts, loss, duplication, delay) derives from one seed, so a run can be
 * replayed exactly. After every tick a safety checker records {@link #violations()}:
 * two leaders in one term, or two nodes committing different entries at the same index.
 */
public final class SimCluster {
    private static final int MIN_ELECTION = 30;
    private static final int MAX_ELECTION = 60;
    private static final int HEARTBEAT = 5;

    private record InFlight(long deliverAt, long seq, int from, int to, Message msg) {}

    private final RaftNode[] nodes;
    private final boolean[] down;
    private final Set<Long> blocked = new HashSet<>();
    private final PriorityQueue<InFlight> queue = new PriorityQueue<>(
            java.util.Comparator.comparingLong(InFlight::deliverAt).thenComparingLong(InFlight::seq));
    private final Random netRandom;
    private final double lossProb;
    private final double dupProb;
    private final int minDelay;
    private final int maxDelay;
    private long time;
    private long seq;

    private final Map<Integer, Integer> leaderByTerm = new HashMap<>();
    private final List<LogEntry> globalCommitted = new ArrayList<>();
    private final List<String> violations = new ArrayList<>();

    public SimCluster(int n, long seed, double lossProb, double dupProb, int minDelay, int maxDelay) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        if (minDelay < 1 || maxDelay < minDelay) {
            throw new IllegalArgumentException("delays must satisfy 1 <= min <= max");
        }
        if (lossProb < 0 || lossProb >= 1 || dupProb < 0 || dupProb >= 1) {
            throw new IllegalArgumentException("probabilities must be in [0,1)");
        }
        this.lossProb = lossProb;
        this.dupProb = dupProb;
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.netRandom = new Random(seed ^ 0x5DEECE66DL);
        this.nodes = new RaftNode[n];
        this.down = new boolean[n];
        for (int i = 0; i < n; i++) {
            final int from = i;
            nodes[i] = new RaftNode(i, n, (to, m) -> send(from, to, m),
                    new Random(seed * 1_000_003L + i), MIN_ELECTION, MAX_ELECTION, HEARTBEAT);
        }
    }

    // ---- control ---------------------------------------------------------------------------

    public RaftNode node(int i) { return nodes[i]; }

    public int size() { return nodes.length; }

    public long time() { return time; }

    /** Advances time by one tick: delivers due messages, then ticks every live node. */
    public void tick() {
        time++;
        while (!queue.isEmpty() && queue.peek().deliverAt() <= time) {
            InFlight f = queue.poll();
            if (!down[f.to()] && !isBlocked(f.from(), f.to())) {
                nodes[f.to()].receive(f.from(), f.msg());
            }
        }
        for (int i = 0; i < nodes.length; i++) {
            if (!down[i]) {
                nodes[i].tick();
            }
        }
        check();
    }

    /** Stops a node: it neither ticks nor receives, and what it sends is dropped. */
    public void crash(int i) { down[i] = true; }

    /** Brings a crashed (or running) node back with durable state only. */
    public void restart(int i) {
        down[i] = false;
        nodes[i].restart();
    }

    public boolean isDown(int i) { return down[i]; }

    /** Cuts the link between a and b in both directions (a == b is ignored). */
    public void block(int a, int b) {
        if (a != b) {
            blocked.add(key(a, b));
            blocked.add(key(b, a));
        }
    }

    public void healAll() { blocked.clear(); }

    /** The live leader with the highest term, if any. */
    public Optional<Integer> leader() {
        int best = -1;
        for (int i = 0; i < nodes.length; i++) {
            if (!down[i] && nodes[i].role() == RaftNode.Role.LEADER
                    && (best == -1 || nodes[i].term() > nodes[best].term())) {
                best = i;
            }
        }
        return best == -1 ? Optional.empty() : Optional.of(best);
    }

    /** Proposes a command on the current leader; returns its log index, or empty without a leader. */
    public Optional<Integer> propose(String command) {
        Optional<Integer> l = leader();
        if (l.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(nodes[l.get()].propose(command));
    }

    /** Safety violations seen so far; empty when the run is correct. */
    public List<String> violations() { return List.copyOf(violations); }

    /** Every command ever committed on any node, in log order, without no-ops. */
    public List<String> globalCommitted() {
        return globalCommitted.stream().filter(e -> !e.isNoop()).map(LogEntry::command).toList();
    }

    // ---- internals -------------------------------------------------------------------------

    private static long key(int a, int b) { return ((long) a << 32) | b; }

    private boolean isBlocked(int a, int b) { return blocked.contains(key(a, b)); }

    private void send(int from, int to, Message m) {
        if (down[from] || isBlocked(from, to) || netRandom.nextDouble() < lossProb) {
            return;
        }
        enqueue(from, to, m);
        if (netRandom.nextDouble() < dupProb) {
            enqueue(from, to, m);
        }
    }

    private void enqueue(int from, int to, Message m) {
        int delay = minDelay + netRandom.nextInt(maxDelay - minDelay + 1);
        queue.add(new InFlight(time + delay, seq++, from, to, m));
    }

    private void check() {
        for (int i = 0; i < nodes.length; i++) {
            RaftNode n = nodes[i];
            if (down[i]) {
                continue;
            }
            if (n.role() == RaftNode.Role.LEADER) {
                Integer prev = leaderByTerm.putIfAbsent(n.term(), i);
                if (prev != null && prev != i) {
                    violations.add("t=" + time + ": two leaders in term " + n.term() + ": " + prev + " and " + i);
                }
            }
            List<LogEntry> log = n.log();
            for (int idx = 1; idx <= n.commitIndex(); idx++) {
                LogEntry e = log.get(idx - 1);
                if (idx <= globalCommitted.size()) {
                    if (!globalCommitted.get(idx - 1).equals(e)) {
                        violations.add("t=" + time + ": node " + i + " committed " + e + " at index " + idx
                                + " but " + globalCommitted.get(idx - 1) + " was committed before");
                    }
                } else {
                    globalCommitted.add(e);
                }
            }
        }
    }
}
