package ae.swim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/** Deterministic in-process network: latency 1 tick, seeded loss, crashes, blocked links, trace. */
public final class Simulation {
    private record Envelope(long at, long order, Message msg) {}

    private final Map<String, SwimNode> nodes = new TreeMap<>();
    private final Set<String> crashed = new HashSet<>();
    private final Set<String> blocked = new HashSet<>();
    private final PriorityQueue<Envelope> queue = new PriorityQueue<>(
        (a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.order, b.order));
    private final List<String> trace = new ArrayList<>();
    private final Random rng;
    private final double dropProbability;
    private final Invariants invariants = new Invariants();
    private long now;
    private long order;

    public Simulation(Collection<String> ids, Config cfg, long seed, double dropProbability) {
        if (dropProbability < 0 || dropProbability >= 1) {
            throw new IllegalArgumentException("dropProbability must be in [0,1)");
        }
        this.rng = new Random(seed);
        this.dropProbability = dropProbability;
        long i = 0;
        for (String id : new java.util.TreeSet<>(ids)) {
            List<String> peers = new ArrayList<>(ids);
            peers.remove(id);
            nodes.put(id, new SwimNode(id, peers, cfg, seed * 1_000_003L + (i++)));
        }
    }

    public SwimNode node(String id) {
        SwimNode n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("unknown node " + id);
        }
        return n;
    }

    public int size() {
        return nodes.size();
    }

    public long now() {
        return now;
    }

    public boolean isCrashed(String id) {
        return crashed.contains(id);
    }

    public List<String> trace() {
        return Collections.unmodifiableList(trace);
    }

    public void crash(String id) {
        node(id);
        crashed.add(id);
    }

    public void blockLink(String a, String b) {
        node(a);
        node(b);
        blocked.add(linkKey(a, b));
    }

    public void healLink(String a, String b) {
        blocked.remove(linkKey(a, b));
    }

    private static String linkKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /** One tick: deliver everything due, run every live node's timers, then check the invariants. */
    public void step() {
        while (!queue.isEmpty() && queue.peek().at <= now) {
            Message m = queue.poll().msg;
            if (crashed.contains(m.to())) {
                trace.add(now + " DROP(crashed) " + describe(m));
                continue;
            }
            trace.add(now + " " + describe(m));
            send(node(m.to()).receive(m, now));
        }
        for (SwimNode n : nodes.values()) {
            if (!crashed.contains(n.id())) {
                send(n.tick(now));
            }
        }
        invariants.check(this);
        now++;
    }

    public void run(int ticks) {
        for (int i = 0; i < ticks; i++) {
            step();
        }
    }

    /** Steps until the condition holds; returns the tick at which it first held, or -1 after maxTicks. */
    public long runUntil(BooleanSupplier condition, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            step();
            if (condition.getAsBoolean()) {
                return now;
            }
        }
        return -1;
    }

    private void send(List<Message> out) {
        for (Message m : out) {
            boolean lost = blocked.contains(linkKey(m.from(), m.to()))
                || (dropProbability > 0 && rng.nextDouble() < dropProbability);
            if (lost) {
                trace.add(now + " DROP " + describe(m));
            } else {
                queue.add(new Envelope(now + 1, order++, m));
            }
        }
    }

    private static String describe(Message m) {
        return m.from() + "->" + m.to() + " " + m.getClass().getSimpleName() + " " + m.updates();
    }

    void checkInvariants() {
        invariants.check(this);
    }

    SwimNode rawNode(String id) {
        return nodes.get(id);
    }

    Collection<String> ids() {
        return nodes.keySet();
    }
}
