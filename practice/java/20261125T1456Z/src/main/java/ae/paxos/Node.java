package ae.paxos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One replica: acceptor, proposer (multi-decree, one ballot covers all slots) and learner.
 * Pure state machine: input is {@link #submit}, {@link #tick}, {@link #receive}; output is
 * {@link #drainOutbox()}. Time is a caller-supplied tick counter.
 */
public final class Node {
    /** Filler value proposed for a gap slot nobody accepted anything in. */
    public static final String NOOP = "<noop>";

    /** Role of the proposer side. */
    public enum Role { FOLLOWER, CANDIDATE, LEADER }

    /** A value accepted at a ballot. */
    public record Accepted(Ballot ballot, String value) { }

    private final int id;
    private final int n;
    private final List<Msg> outbox = new ArrayList<>();

    // durable
    private Ballot promised = Ballot.NONE;
    private long ownRound;
    private final TreeMap<Integer, Accepted> accepted = new TreeMap<>();
    private final TreeMap<Integer, String> decided = new TreeMap<>();
    private long rng;

    // volatile
    private Role role = Role.FOLLOWER;
    private Ballot ballot;
    private final Map<Integer, Map<Integer, Accepted>> promises = new HashMap<>();
    private final TreeMap<Integer, String> proposing = new TreeMap<>();
    private final Map<Integer, Set<Integer>> acks = new HashMap<>();
    private final LinkedHashSet<String> owned = new LinkedHashSet<>();
    private int nextSlot;
    private int knownLeader = -1;
    private long deadline;
    private long backoffUntil;
    private long phaseStart;
    private long lastResend;
    private long lastBeat;
    private long lastDecideBroadcast;
    private long lastForward;
    private long lastSync;
    private int syncPeer;

    public Node(int id, int n) {
        if (n < 1 || id < 0 || id >= n) throw new IllegalArgumentException("bad id/n");
        this.id = id;
        this.n = n;
        this.rng = 88172645463325252L ^ (id + 1) * 0x9E3779B97F4A7C15L;
    }

    private int quorum() {
        return n / 2 + 1;
    }

    private int jitter() {
        rng ^= rng << 13;
        rng ^= rng >>> 7;
        rng ^= rng << 17;
        return (int) Long.remainderUnsigned(rng, 8);
    }

    // ---- accessors ----
    public int id() { return id; }

    public Role role() { return role; }

    public Ballot promised() { return promised; }

    public Map<Integer, Accepted> accepted() { return Map.copyOf(accepted); }

    public Map<Integer, String> decided() { return Map.copyOf(decided); }

    /** Returns and clears the messages produced since the last call. */
    public List<Msg> drainOutbox() {
        List<Msg> out = new ArrayList<>(outbox);
        outbox.clear();
        return out;
    }

    /** The contiguous decided prefix, without NOOPs and without repeated commands. */
    public List<String> appliedLog() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int s = 0; decided.containsKey(s); s++) {
            String v = decided.get(s);
            if (!v.equals(NOOP) && seen.add(v)) out.add(v);
        }
        return out;
    }

    // ---- input ----
    /** A client hands this node a command; it is retried until it shows up decided. */
    public void submit(String command, long now) {
        if (command == null || command.equals(NOOP)) throw new IllegalArgumentException("bad command");
        if (decided.containsValue(command)) return;
        owned.add(command);
        if (knownLeader < 0) deadline = Math.max(deadline, now + 2 + 3L * id);
        if (deadline < now) deadline = now + 2 + 3L * id;
    }

    /** Volatile state is lost; promised, ballots, accepted and decided survive. */
    public void crash() {
        role = Role.FOLLOWER;
        ballot = null;
        promises.clear();
        proposing.clear();
        acks.clear();
        owned.clear();
        outbox.clear();
        knownLeader = -1;
        nextSlot = 0;
        deadline = 0;
        backoffUntil = 0;
    }

    public void tick(long now) {
        owned.removeIf(decided::containsValue);
        if (n > 1 && now - lastSync >= 20) {
            lastSync = now;
            syncPeer = (syncPeer + 1) % n;
            if (syncPeer == id) syncPeer = (syncPeer + 1) % n;
            int first = 0;
            while (decided.containsKey(first)) first++;
            send(new Msg.Sync(id, syncPeer, first));
        }
        switch (role) {
            case FOLLOWER -> {
                if (!owned.isEmpty() && now >= deadline && now >= backoffUntil) {
                    startPhase1(now);
                } else if (!owned.isEmpty() && knownLeader >= 0 && now - lastForward >= 10) {
                    lastForward = now;
                    for (String c : owned) send(new Msg.Forward(id, knownLeader, c));
                }
            }
            case CANDIDATE -> {
                if (now - phaseStart > 15) {
                    stepDown(now);
                }
            }
            case LEADER -> leaderTick(now);
        }
    }

    private void leaderTick(long now) {
        driveOwned();
        if (now - lastResend >= 6) {
            lastResend = now;
            for (Map.Entry<Integer, String> e : proposing.entrySet()) {
                for (int p = 0; p < n; p++) {
                    if (p != id && !acks.get(e.getKey()).contains(p)) {
                        send(new Msg.Accept(id, p, ballot, e.getKey(), e.getValue()));
                    }
                }
            }
        }
        if (now - lastBeat >= 5) {
            lastBeat = now;
            for (int p = 0; p < n; p++) if (p != id) send(new Msg.Heartbeat(id, p, ballot));
        }
        if (now - lastDecideBroadcast >= 10) {
            lastDecideBroadcast = now;
            for (Map.Entry<Integer, String> e : decided.entrySet()) {
                for (int p = 0; p < n; p++) if (p != id) send(new Msg.Decide(id, p, e.getKey(), e.getValue()));
            }
        }
    }

    public void receive(Msg m, long now) {
        switch (m) {
            case Msg.Prepare p -> onPrepare(p, now);
            case Msg.Promise p -> onPromise(p, now);
            case Msg.Nack k -> onNack(k, now);
            case Msg.Accept a -> onAccept(a, now);
            case Msg.Accepted a -> onAccepted(a);
            case Msg.Decide d -> decide(d.slot(), d.value());
            case Msg.Heartbeat h -> onHeartbeat(h, now);
            case Msg.Sync y -> {
                for (Map.Entry<Integer, String> e : decided.tailMap(y.firstUndecided()).entrySet()) {
                    send(new Msg.Decide(id, y.from(), e.getKey(), e.getValue()));
                }
            }
            case Msg.Forward f -> {
                if (role == Role.LEADER && !decided.containsValue(f.command())) owned.add(f.command());
            }
        }
    }

    // ---- acceptor ----
    private void onPrepare(Msg.Prepare p, long now) {
        if (p.ballot().compareTo(promised) >= 0) {
            raisePromise(p.ballot(), now);
            send(new Msg.Promise(id, p.from(), p.ballot(), accepted));
        } else {
            send(new Msg.Nack(id, p.from(), promised));
        }
    }

    private void onAccept(Msg.Accept a, long now) {
        if (a.ballot().compareTo(promised) >= 0) {
            raisePromise(a.ballot(), now);
            accepted.put(a.slot(), new Accepted(a.ballot(), a.value()));
            send(new Msg.Accepted(id, a.from(), a.ballot(), a.slot()));
            hearLeader(a.from(), now);
        } else {
            send(new Msg.Nack(id, a.from(), promised));
        }
    }

    private void raisePromise(Ballot b, long now) {
        promised = b;
        if (role != Role.FOLLOWER && ballot.compareTo(b) < 0) stepDown(now);
    }

    private void onHeartbeat(Msg.Heartbeat h, long now) {
        if (h.ballot().compareTo(promised) < 0) return;
        raisePromise(h.ballot(), now);
        hearLeader(h.from(), now);
    }

    private void hearLeader(int leader, long now) {
        if (leader == id) return;
        knownLeader = leader;
        deadline = now + 25 + 3L * id;
    }

    // ---- proposer ----
    private void startPhase1(long now) {
        ownRound = Math.max(ownRound, promised.round()) + 1;
        ballot = new Ballot(ownRound, id);
        promised = ballot;
        role = Role.CANDIDATE;
        phaseStart = now;
        promises.clear();
        promises.put(id, new HashMap<>(accepted));
        for (int p = 0; p < n; p++) if (p != id) send(new Msg.Prepare(id, p, ballot));
        if (promises.size() >= quorum()) becomeLeader(now);
    }

    private void onPromise(Msg.Promise p, long now) {
        if (role != Role.CANDIDATE || !p.ballot().equals(ballot)) return;
        promises.put(p.from(), p.accepted());
        if (promises.size() >= quorum()) becomeLeader(now);
    }

    private void becomeLeader(long now) {
        role = Role.LEADER;
        knownLeader = id;
        lastResend = now;
        lastBeat = now;
        lastDecideBroadcast = now;
        Map<Integer, Accepted> best = new HashMap<>();
        for (Map<Integer, Accepted> m : promises.values()) {
            for (Map.Entry<Integer, Accepted> e : m.entrySet()) {
                Accepted cur = best.get(e.getKey());
                if (cur == null || e.getValue().ballot().compareTo(cur.ballot()) > 0) best.put(e.getKey(), e.getValue());
            }
        }
        int hi = -1;
        for (int s : best.keySet()) hi = Math.max(hi, s);
        if (!decided.isEmpty()) hi = Math.max(hi, decided.lastKey());
        int lo = 0;
        while (decided.containsKey(lo)) lo++;
        proposing.clear();
        acks.clear();
        for (int s = lo; s <= hi; s++) {
            if (decided.containsKey(s)) continue;
            Accepted a = best.get(s);
            propose(s, a == null ? NOOP : a.value());
        }
        nextSlot = hi + 1;
        driveOwned();
    }

    private void driveOwned() {
        for (String cmd : new ArrayList<>(owned)) {
            if (decided.containsValue(cmd)) {
                owned.remove(cmd);
            } else if (!proposing.containsValue(cmd)) {
                propose(nextSlot++, cmd);
            }
        }
    }

    private void propose(int slot, String value) {
        proposing.put(slot, value);
        Set<Integer> a = new HashSet<>();
        a.add(id);
        acks.put(slot, a);
        accepted.put(slot, new Accepted(ballot, value));
        for (int p = 0; p < n; p++) if (p != id) send(new Msg.Accept(id, p, ballot, slot, value));
        if (a.size() >= quorum()) chosen(slot);
    }

    private void onAccepted(Msg.Accepted a) {
        if (role != Role.LEADER || !a.ballot().equals(ballot)) return;
        Set<Integer> set = acks.get(a.slot());
        if (set == null || !proposing.containsKey(a.slot())) return;
        set.add(a.from());
        if (set.size() >= quorum()) chosen(a.slot());
    }

    private void chosen(int slot) {
        String v = proposing.get(slot);
        decide(slot, v);
        for (int p = 0; p < n; p++) if (p != id) send(new Msg.Decide(id, p, slot, v));
    }

    private void onNack(Msg.Nack k, long now) {
        if (role == Role.FOLLOWER) return;
        if (k.promised().compareTo(ballot) > 0) {
            ownRound = Math.max(ownRound, k.promised().round());
            stepDown(now);
        }
    }

    private void stepDown(long now) {
        role = Role.FOLLOWER;
        proposing.clear();
        acks.clear();
        promises.clear();
        backoffUntil = now + 2 + jitter();
    }

    private void decide(int slot, String value) {
        String old = decided.putIfAbsent(slot, value);
        if (old != null && !old.equals(value)) {
            throw new IllegalStateException("conflicting decision at slot " + slot + ": " + old + " vs " + value);
        }
        proposing.remove(slot);
        acks.remove(slot);
        nextSlot = Math.max(nextSlot, slot + 1);
    }

    private void send(Msg m) {
        outbox.add(m);
    }

    // ---- test hooks (package-private) ----
    void forceDecided(int slot, String value) { decided.put(slot, value); }

    void forcePromised(Ballot b) { promised = b; }
}
