package ae.locks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic, non-blocking, multi-granularity lock manager with deadlock detection.
 * See {@code TASK.md} for the full contract. All public methods are synchronized.
 */
public final class LockManager {
    /** Lifecycle of a transaction. */
    public enum TxnState { ACTIVE, BLOCKED, COMMITTED, ABORTED }

    /** Result of {@link #acquire}. */
    public enum Status { GRANTED, WAITING, ABORTED }

    /** Side effects of an operation: transactions woken up and deadlock victims. */
    public record Events(List<Long> unblocked, List<Long> aborted) { }

    /** Result of {@link #acquire}: the requester's status plus side effects. */
    public record Outcome(Status status, Events events) { }

    private record Step(String resource, LockMode mode) { }

    private static final class Request {
        final Txn txn;
        final LockMode target;
        final boolean upgrade;

        Request(Txn txn, LockMode target, boolean upgrade) {
            this.txn = txn;
            this.target = target;
            this.upgrade = upgrade;
        }
    }

    private static final class Txn {
        final long id;
        final int age;
        TxnState state = TxnState.ACTIVE;
        boolean shrinking;
        final Deque<Step> plan = new ArrayDeque<>();
        final Map<String, LockMode> held = new TreeMap<>();
        Request pending;
        String waitingAt;

        Txn(long id, int age) {
            this.id = id;
            this.age = age;
        }
    }

    private static final class Node {
        final Map<Long, LockMode> holders = new LinkedHashMap<>();
        final LinkedList<Request> queue = new LinkedList<>();
    }

    /** Mutable collector for the events of one public operation. */
    private static final class Collector {
        final Set<Long> unblocked = new LinkedHashSet<>();
        final Set<Long> aborted = new LinkedHashSet<>();

        Events toEvents() {
            return new Events(List.copyOf(unblocked), List.copyOf(aborted));
        }
    }

    private final Map<Long, Txn> txns = new HashMap<>();
    private final Map<String, Node> nodes = new TreeMap<>();
    private int nextAge;

    // ---- public API --------------------------------------------------------------------

    /** Registers a new transaction; its age is the order of this call. */
    public synchronized void begin(long txn) {
        if (txns.containsKey(txn)) {
            throw new IllegalArgumentException("transaction id already used: " + txn);
        }
        txns.put(txn, new Txn(txn, nextAge++));
    }

    /** Requests {@code mode} on {@code resource}, taking intention locks on its ancestors first. */
    public synchronized Outcome acquire(long txn, String resource, LockMode mode) {
        Objects.requireNonNull(mode, "mode");
        validatePath(resource);
        Txn t = txn(txn);
        if (t.state == TxnState.BLOCKED) {
            throw new IllegalStateException("transaction " + txn + " is blocked");
        }
        if (t.state != TxnState.ACTIVE) {
            throw new IllegalStateException("transaction " + txn + " has ended");
        }
        if (t.shrinking) {
            throw new IllegalStateException("transaction " + txn + " is shrinking (2PL)");
        }
        for (String ancestor : ancestors(resource)) {
            t.plan.addLast(new Step(ancestor, mode.intention()));
        }
        t.plan.addLast(new Step(resource, mode));
        Collector ev = new Collector();
        if (!advance(t)) {
            resolveDeadlocks(ev);
        }
        ev.unblocked.remove(txn);
        Status status;
        if (t.state == TxnState.ABORTED) {
            status = Status.ABORTED;
        } else if (t.state == TxnState.BLOCKED) {
            status = Status.WAITING;
        } else {
            status = Status.GRANTED;
        }
        return new Outcome(status, ev.toEvents());
    }

    /** Commits an ACTIVE transaction and releases all its locks. */
    public synchronized Events commit(long txn) {
        Txn t = txn(txn);
        if (t.state == TxnState.BLOCKED) {
            throw new IllegalStateException("transaction " + txn + " is blocked");
        }
        requireLive(t);
        return finish(t, TxnState.COMMITTED);
    }

    /** Aborts a live (ACTIVE or BLOCKED) transaction and releases all its locks. */
    public synchronized Events abort(long txn) {
        Txn t = txn(txn);
        requireLive(t);
        return finish(t, TxnState.ABORTED);
    }

    /** Early release of one lock that has no held descendants; starts the shrinking phase. */
    public synchronized Events unlock(long txn, String resource) {
        validatePath(resource);
        Txn t = txn(txn);
        if (t.state != TxnState.ACTIVE) {
            throw new IllegalStateException("transaction " + txn + " is not active");
        }
        if (!t.held.containsKey(resource)) {
            throw new IllegalArgumentException("transaction " + txn + " does not hold " + resource);
        }
        String prefix = resource + "/";
        for (String other : t.held.keySet()) {
            if (other.startsWith(prefix)) {
                throw new IllegalStateException("still holds " + other + " below " + resource);
            }
        }
        t.shrinking = true;
        Node node = nodes.get(resource);
        node.holders.remove(txn);
        t.held.remove(resource);
        Collector ev = new Collector();
        drain(resource, ev);
        resolveDeadlocks(ev);
        return ev.toEvents();
    }

    public synchronized TxnState state(long txn) {
        return txn(txn).state;
    }

    public synchronized Optional<LockMode> held(long txn, String resource) {
        return Optional.ofNullable(txn(txn).held.get(resource));
    }

    /** Sorted copy of everything the transaction holds. */
    public synchronized Map<String, LockMode> locks(long txn) {
        return new TreeMap<>(txn(txn).held);
    }

    /** Sorted-by-id copy of the current holders of a resource. */
    public synchronized Map<Long, LockMode> holders(String resource) {
        Node n = nodes.get(resource);
        return n == null ? new TreeMap<>() : new TreeMap<>(n.holders);
    }

    /** Ids of waiting transactions in queue order. */
    public synchronized List<Long> queue(String resource) {
        List<Long> out = new ArrayList<>();
        Node n = nodes.get(resource);
        if (n != null) {
            for (Request r : n.queue) {
                out.add(r.txn.id);
            }
        }
        return out;
    }

    // ---- core --------------------------------------------------------------------------

    /** Runs the plan of {@code t} until it completes (true) or blocks (false). */
    private boolean advance(Txn t) {
        t.state = TxnState.ACTIVE;
        while (!t.plan.isEmpty()) {
            Step step = t.plan.peekFirst();
            LockMode current = t.held.get(step.resource());
            LockMode target = current == null ? step.mode() : current.join(step.mode());
            if (current != null && current == target) {
                t.plan.removeFirst();
                continue;
            }
            boolean upgrade = current != null;
            Node node = nodes.computeIfAbsent(step.resource(), k -> new Node());
            if (holdersCompatible(node, t.id, target) && (upgrade || node.queue.isEmpty())) {
                node.holders.put(t.id, target);
                t.held.put(step.resource(), target);
                t.plan.removeFirst();
                continue;
            }
            Request req = new Request(t, target, upgrade);
            if (upgrade) {
                int i = 0;
                while (i < node.queue.size() && node.queue.get(i).upgrade) {
                    i++;
                }
                node.queue.add(i, req);
            } else {
                node.queue.addLast(req);
            }
            t.pending = req;
            t.waitingAt = step.resource();
            t.state = TxnState.BLOCKED;
            return false;
        }
        return true;
    }

    private static boolean holdersCompatible(Node node, long self, LockMode target) {
        for (Map.Entry<Long, LockMode> h : node.holders.entrySet()) {
            if (h.getKey() != self && !h.getValue().compatibleWith(target)) {
                return false;
            }
        }
        return true;
    }

    /** Grants queue heads of {@code name} while possible; resumes the granted transactions' plans. */
    private void drain(String name, Collector ev) {
        Node node = nodes.get(name);
        if (node == null) {
            return;
        }
        while (!node.queue.isEmpty()) {
            Request r = node.queue.peekFirst();
            if (!holdersCompatible(node, r.txn.id, r.target)) {
                break;
            }
            node.queue.removeFirst();
            Txn t = r.txn;
            node.holders.put(t.id, r.target);
            t.held.put(name, r.target);
            t.pending = null;
            t.waitingAt = null;
            t.plan.removeFirst();
            if (advance(t)) {
                ev.unblocked.add(t.id);
            }
        }
        dropIfEmpty(name);
    }

    private void dropIfEmpty(String name) {
        Node n = nodes.get(name);
        if (n != null && n.holders.isEmpty() && n.queue.isEmpty()) {
            nodes.remove(name);
        }
    }

    /** Releases everything {@code t} holds or waits for, ends it, and processes the consequences. */
    private Events finish(Txn t, TxnState end) {
        Collector ev = new Collector();
        release(t, end, ev);
        resolveDeadlocks(ev);
        return ev.toEvents();
    }

    private void release(Txn t, TxnState end, Collector ev) {
        Set<String> touched = new TreeSet<>();
        if (t.pending != null) {
            nodes.get(t.waitingAt).queue.remove(t.pending);
            touched.add(t.waitingAt);
            t.pending = null;
            t.waitingAt = null;
        }
        for (String r : t.held.keySet()) {
            nodes.get(r).holders.remove(t.id);
            touched.add(r);
        }
        t.held.clear();
        t.plan.clear();
        t.state = end;
        for (String name : touched) {
            drain(name, ev);
        }
    }

    // ---- deadlock detection ------------------------------------------------------------

    private void resolveDeadlocks(Collector ev) {
        while (true) {
            List<Txn> cycle = findCycle();
            if (cycle == null) {
                return;
            }
            Txn victim = cycle.get(0);
            for (Txn c : cycle) {
                if (c.age > victim.age) {
                    victim = c;
                }
            }
            release(victim, TxnState.ABORTED, ev);
            ev.aborted.add(victim.id);
        }
    }

    private List<Txn> blockedByAge() {
        List<Txn> blocked = new ArrayList<>();
        for (Txn t : txns.values()) {
            if (t.state == TxnState.BLOCKED) {
                blocked.add(t);
            }
        }
        blocked.sort((a, b) -> Integer.compare(a.age, b.age));
        return blocked;
    }

    private List<Txn> waitsFor(Txn t) {
        Node node = nodes.get(t.waitingAt);
        Request mine = t.pending;
        Set<Txn> out = new LinkedHashSet<>();
        for (Map.Entry<Long, LockMode> h : node.holders.entrySet()) {
            if (h.getKey() != t.id && !h.getValue().compatibleWith(mine.target)) {
                out.add(txns.get(h.getKey()));
            }
        }
        for (Request earlier : node.queue) {
            if (earlier == mine) {
                break;
            }
            if (!earlier.target.compatibleWith(mine.target)) {
                out.add(earlier.txn);
            }
        }
        List<Txn> sorted = new ArrayList<>(out);
        sorted.sort((a, b) -> Integer.compare(a.age, b.age));
        return sorted;
    }

    /** Returns the transactions of some cycle in the wait-for graph, or null. */
    private List<Txn> findCycle() {
        Set<Txn> done = new HashSet<>();
        for (Txn start : blockedByAge()) {
            if (done.contains(start)) {
                continue;
            }
            List<Txn> path = new ArrayList<>();
            List<Txn> found = dfs(start, path, new HashSet<>(), done);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private List<Txn> dfs(Txn t, List<Txn> path, Set<Txn> onPath, Set<Txn> done) {
        if (t.state != TxnState.BLOCKED || done.contains(t)) {
            return null;
        }
        path.add(t);
        onPath.add(t);
        for (Txn next : waitsFor(t)) {
            if (onPath.contains(next)) {
                return new ArrayList<>(path.subList(path.indexOf(next), path.size()));
            }
            List<Txn> found = dfs(next, path, onPath, done);
            if (found != null) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        onPath.remove(t);
        done.add(t);
        return null;
    }

    // ---- validation helpers ------------------------------------------------------------

    private Txn txn(long id) {
        Txn t = txns.get(id);
        if (t == null) {
            throw new IllegalArgumentException("unknown transaction " + id);
        }
        return t;
    }

    private static void requireLive(Txn t) {
        if (t.state == TxnState.COMMITTED || t.state == TxnState.ABORTED) {
            throw new IllegalStateException("transaction " + t.id + " has ended");
        }
    }

    private static void validatePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("empty resource");
        }
        for (String seg : path.split("/", -1)) {
            if (seg.isEmpty()) {
                throw new IllegalArgumentException("bad resource path: " + path);
            }
        }
    }

    /** Proper ancestors of a path, root first. */
    private static List<String> ancestors(String path) {
        List<String> out = new ArrayList<>();
        int i = path.indexOf('/');
        while (i >= 0) {
            out.add(path.substring(0, i));
            i = path.indexOf('/', i + 1);
        }
        return out;
    }

    // ---- invariants (package-private, used by tests) -----------------------------------

    /** Throws {@link IllegalStateException} if any structural invariant is violated. */
    synchronized void checkInvariants() {
        Set<Txn> queued = new HashSet<>();
        for (Map.Entry<String, Node> e : nodes.entrySet()) {
            String name = e.getKey();
            Node n = e.getValue();
            check(!(n.holders.isEmpty() && n.queue.isEmpty()), "empty node kept: " + name);
            List<Map.Entry<Long, LockMode>> hs = new ArrayList<>(n.holders.entrySet());
            for (int i = 0; i < hs.size(); i++) {
                Txn t = txns.get(hs.get(i).getKey());
                check(t.state == TxnState.ACTIVE || t.state == TxnState.BLOCKED, "ended txn holds " + name);
                check(t.held.get(name) == hs.get(i).getValue(), "holder table mismatch at " + name);
                for (int j = i + 1; j < hs.size(); j++) {
                    check(hs.get(i).getValue().compatibleWith(hs.get(j).getValue()),
                            "incompatible holders at " + name + ": " + hs);
                }
            }
            for (Request r : n.queue) {
                check(r.txn.state == TxnState.BLOCKED, "queued txn not blocked at " + name);
                check(r.txn.pending == r && name.equals(r.txn.waitingAt), "pending mismatch at " + name);
                check(queued.add(r.txn), "txn queued twice: " + r.txn.id);
                check(r.txn.plan.peekFirst().resource().equals(name), "plan head mismatch at " + name);
                check(r.upgrade == n.holders.containsKey(r.txn.id), "upgrade flag wrong at " + name);
            }
            if (!n.queue.isEmpty()) {
                Request head = n.queue.peekFirst();
                check(!holdersCompatible(n, head.txn.id, head.target), "grantable queue head at " + name);
            }
            boolean seenFresh = false;
            for (Request r : n.queue) {
                check(!(seenFresh && r.upgrade), "upgrade queued behind fresh request at " + name);
                seenFresh |= !r.upgrade;
            }
        }
        for (Txn t : txns.values()) {
            check((t.state == TxnState.BLOCKED) == queued.contains(t), "blocked/queue mismatch for " + t.id);
            if (t.state == TxnState.COMMITTED || t.state == TxnState.ABORTED) {
                check(t.held.isEmpty() && t.pending == null && t.plan.isEmpty(), "ended txn not clean: " + t.id);
            }
            for (Map.Entry<String, LockMode> h : t.held.entrySet()) {
                for (String a : ancestors(h.getKey())) {
                    LockMode have = t.held.get(a);
                    check(have != null && have.covers(h.getValue().intention()),
                            "txn " + t.id + " lacks intention on " + a + " for " + h.getKey());
                }
            }
        }
        check(findCycle() == null, "wait-for cycle remains");
        Set<Long> ids = new TreeSet<>(Collections.unmodifiableSet(txns.keySet()));
        check(ids.size() == txns.size(), "ids");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("invariant violated: " + message);
        }
    }
}
