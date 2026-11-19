package ae.swim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;

/**
 * One SWIM member as a pure state machine: callers feed it time and messages and send what it returns.
 * Membership is static (the peer set is fixed at construction) and DEAD is final.
 */
public final class SwimNode {
    private static final class Entry {
        Status status = Status.ALIVE;
        int incarnation;
        long suspectedAt;
    }

    private static final class Pending {
        final String target;
        final int seq;
        final long start;
        boolean acked;
        boolean indirectSent;

        Pending(String target, int seq, long start) {
            this.target = target;
            this.seq = seq;
            this.start = start;
        }
    }

    private static final class Buffered {
        final Update update;
        int sent;

        Buffered(Update update) {
            this.update = update;
        }
    }

    private record Relay(String origin, int originSeq, long expires) {}

    private final String id;
    private final Config cfg;
    private final Random rng;
    private final Map<String, Entry> table = new TreeMap<>();
    private final Map<String, Buffered> gossip = new LinkedHashMap<>();
    private final Map<Integer, Relay> relays = new HashMap<>();
    private final Deque<String> probeQueue = new ArrayDeque<>();
    private Pending pending;
    private int seqCounter;
    private int selfIncarnation;
    private boolean declaredDead;

    public SwimNode(String id, Collection<String> peers, Config cfg, long seed) {
        this.id = Objects.requireNonNull(id);
        this.cfg = Objects.requireNonNull(cfg);
        this.rng = new Random(seed);
        for (String p : peers) {
            if (p.equals(id)) {
                throw new IllegalArgumentException("a node is not its own peer: " + id);
            }
            if (table.put(p, new Entry()) != null) {
                throw new IllegalArgumentException("duplicate peer: " + p);
            }
        }
    }

    public String id() {
        return id;
    }

    public Status status(String peer) {
        return entry(peer).status;
    }

    public int incarnation(String peer) {
        return entry(peer).incarnation;
    }

    public int selfIncarnation() {
        return selfIncarnation;
    }

    public boolean isDeclaredDead() {
        return declaredDead;
    }

    public int pendingGossip() {
        return gossip.size();
    }

    /** Snapshot of this node's opinion about every peer, sorted by name. */
    public Map<String, Status> view() {
        Map<String, Status> out = new TreeMap<>();
        table.forEach((k, v) -> out.put(k, v.status));
        return Collections.unmodifiableMap(out);
    }

    /** Test hook: overwrite the table (used to prove the invariant checker notices corruption). */
    void debugForce(String peer, Status status, int incarnation) {
        Entry e = entry(peer);
        e.status = status;
        e.incarnation = incarnation;
    }

    private Entry entry(String peer) {
        Entry e = table.get(peer);
        if (e == null) {
            throw new IllegalArgumentException("unknown peer: " + peer);
        }
        return e;
    }

    // ---------------------------------------------------------------- timers

    public List<Message> tick(long now) {
        if (declaredDead) {
            return List.of();
        }
        List<Message> out = new ArrayList<>();
        for (Map.Entry<String, Entry> me : table.entrySet()) {
            Entry e = me.getValue();
            if (e.status == Status.SUSPECT && now - e.suspectedAt >= cfg.suspicionTicks()) {
                apply(me.getKey(), new Update(me.getKey(), Status.DEAD, e.incarnation), now);
            }
        }
        relays.values().removeIf(r -> r.expires() <= now);
        if (pending != null) {
            long elapsed = now - pending.start;
            if (!pending.acked && !pending.indirectSent && elapsed >= cfg.pingTimeoutTicks()) {
                pending.indirectSent = true;
                for (String helper : pickHelpers(pending.target)) {
                    out.add(new PingReq(id, helper, pending.target, pending.seq, payloadFor(helper)));
                }
            }
            if (elapsed >= cfg.periodTicks()) {
                if (!pending.acked && table.get(pending.target).status == Status.ALIVE) {
                    Entry e = table.get(pending.target);
                    apply(pending.target, new Update(pending.target, Status.SUSPECT, e.incarnation), now);
                }
                pending = null;
            }
        }
        if (pending == null) {
            String target = nextProbeTarget();
            if (target != null) {
                pending = new Pending(target, ++seqCounter, now);
                out.add(new Ping(id, target, pending.seq, payloadFor(target)));
            }
        }
        return out;
    }

    private String nextProbeTarget() {
        while (true) {
            if (probeQueue.isEmpty()) {
                List<String> live = new ArrayList<>();
                for (Map.Entry<String, Entry> me : table.entrySet()) {
                    if (me.getValue().status != Status.DEAD) {
                        live.add(me.getKey());
                    }
                }
                if (live.isEmpty()) {
                    return null;
                }
                Collections.shuffle(live, rng);
                probeQueue.addAll(live);
            }
            String next = probeQueue.poll();
            if (table.get(next).status != Status.DEAD) {
                return next;
            }
        }
    }

    private List<String> pickHelpers(String target) {
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Entry> me : table.entrySet()) {
            if (!me.getKey().equals(target) && me.getValue().status != Status.DEAD) {
                candidates.add(me.getKey());
            }
        }
        Collections.shuffle(candidates, rng);
        return candidates.subList(0, Math.min(cfg.indirectProbes(), candidates.size()));
    }

    // -------------------------------------------------------------- messages

    public List<Message> receive(Message m, long now) {
        if (!m.to().equals(id)) {
            throw new IllegalArgumentException(id + " got a message for " + m.to());
        }
        if (declaredDead) {
            return List.of();
        }
        merge(m.updates(), now);
        if (declaredDead) {
            return List.of();
        }
        switch (m) {
            case Ping p -> {
                return List.of(new Ack(id, p.from(), p.seq(), id, payloadFor(p.from())));
            }
            case PingReq r -> {
                int relaySeq = ++seqCounter;
                relays.put(relaySeq, new Relay(r.from(), r.seq(), now + cfg.periodTicks()));
                return List.of(new Ping(id, r.target(), relaySeq, payloadFor(r.target())));
            }
            case Ack a -> {
                Relay relay = relays.remove(a.seq());
                if (relay != null) {
                    return List.of(new Ack(id, relay.origin(), relay.originSeq(), a.about(), payloadFor(relay.origin())));
                }
                if (pending != null && pending.seq == a.seq() && pending.target.equals(a.about())) {
                    pending.acked = true;
                }
                return List.of();
            }
        }
    }

    // ---------------------------------------------------------------- gossip

    private void merge(List<Update> updates, long now) {
        for (Update u : updates) {
            if (u.node().equals(id)) {
                if (u.status() == Status.DEAD) {
                    declaredDead = true;
                    return;
                }
                if (u.status() == Status.SUSPECT && u.incarnation() >= selfIncarnation) {
                    selfIncarnation = u.incarnation() + 1;
                    enqueue(new Update(id, Status.ALIVE, selfIncarnation));
                }
                continue;
            }
            Entry cur = table.get(u.node());
            if (cur == null) {
                continue;
            }
            if (u.overrides(new Update(u.node(), cur.status, cur.incarnation))) {
                apply(u.node(), u, now);
            }
        }
    }

    private void apply(String node, Update u, long now) {
        Entry e = table.get(node);
        e.status = u.status();
        e.incarnation = u.incarnation();
        if (u.status() == Status.SUSPECT) {
            e.suspectedAt = now;
        }
        enqueue(u);
    }

    private void enqueue(Update u) {
        gossip.put(u.node(), new Buffered(u));
    }

    private int retransmitLimit() {
        int members = table.size() + 1;
        return cfg.retransmitMult() * (32 - Integer.numberOfLeadingZeros(members)); // ceil(log2(members + 1))
    }

    /** Gossip for one outgoing message: least-sent first, plus what we suspect about the receiver itself. */
    private List<Update> payloadFor(String to) {
        List<Buffered> candidates = new ArrayList<>(gossip.values());
        candidates.sort(Comparator.comparingInt(b -> b.sent)); // stable: ties keep insertion order
        List<Update> out = new ArrayList<>();
        int limit = retransmitLimit();
        for (Buffered b : candidates.subList(0, Math.min(cfg.maxPiggyback(), candidates.size()))) {
            out.add(b.update);
            b.sent++;
            if (b.sent >= limit) {
                gossip.remove(b.update.node(), b);
            }
        }
        Entry about = table.get(to);
        if (about != null && about.status == Status.SUSPECT) {
            Update targeted = new Update(to, Status.SUSPECT, about.incarnation);
            if (!out.contains(targeted)) {
                out.add(targeted);
            }
        }
        return out;
    }
}
