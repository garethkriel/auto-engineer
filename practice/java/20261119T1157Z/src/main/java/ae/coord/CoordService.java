package ae.coord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * ZooKeeper-style coordination kernel: a hierarchical namespace with sessions, ephemeral and
 * sequential nodes, one-shot watches and atomic multi-op transactions.
 *
 * <p>All state changes happen under the service monitor; watchers are invoked after the
 * transaction committed and after the monitor was released, so they may call back in.
 * Every mutation is staged on a copy of the node map ({@link Node} is immutable) and swapped in
 * only when the whole transaction succeeded.
 */
public final class CoordService {
    /** Result of {@link #getData}. */
    public record NodeData(String data, Stat stat) {}

    /** Immutable node; {@code children} is a sorted list of child names. */
    private record Node(String data, long czxid, long mzxid, int version, int cversion,
                        long owner, List<String> children) {
        Stat stat() {
            return new Stat(czxid, mzxid, version, cversion, owner, children.size());
        }
    }

    private record Session(long timeout, long lastBeat) {}

    private record Watch(long session, Consumer<WatchEvent> fn) {}

    private final Clock clock;
    private Map<String, Node> nodes = new HashMap<>();
    private final TreeMap<Long, Session> sessions = new TreeMap<>();
    private final Map<String, List<Watch>> dataWatches = new HashMap<>();
    private final Map<String, List<Watch>> childWatches = new HashMap<>();
    private long zxid;
    private long nextSession = 1;

    public CoordService(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        nodes.put("/", new Node("", 0, 0, 0, 0, 0, List.of()));
    }

    // ---------------------------------------------------------------- sessions

    /** Opens a session that expires {@code timeoutMs} after its last heartbeat. */
    public long connect(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeout must be > 0");
        }
        return locked(-1, out -> {
            long id = nextSession++;
            sessions.put(id, new Session(timeoutMs, clock.now()));
            return id;
        });
    }

    public void heartbeat(long session) {
        locked(session, out -> {
            Session s = sessions.get(session);
            sessions.put(session, new Session(s.timeout(), clock.now()));
            return null;
        });
    }

    /** Ends the session now: removes its ephemerals and watches. */
    public void close(long session) {
        locked(session, out -> {
            endSession(session, out);
            return null;
        });
    }

    /** Forces processing of due expirations (every other call does this too). */
    public void tick() {
        locked(-1, out -> null);
    }

    // ---------------------------------------------------------------- reads

    public NodeData getData(long session, String path, Consumer<WatchEvent> watcher) {
        validate(path);
        return locked(session, out -> {
            Node n = require(path);
            addWatch(dataWatches, path, session, watcher);
            return new NodeData(n.data(), n.stat());
        });
    }

    /** Returns the node's stat; a watcher is registered even when the node is missing. */
    public Optional<Stat> exists(long session, String path, Consumer<WatchEvent> watcher) {
        validate(path);
        return locked(session, out -> {
            addWatch(dataWatches, path, session, watcher);
            Node n = nodes.get(path);
            return n == null ? Optional.<Stat>empty() : Optional.of(n.stat());
        });
    }

    public List<String> getChildren(long session, String path, Consumer<WatchEvent> watcher) {
        validate(path);
        return locked(session, out -> {
            Node n = require(path);
            addWatch(childWatches, path, session, watcher);
            return n.children();
        });
    }

    // ---------------------------------------------------------------- writes

    public String create(long session, String path, String data, CreateMode mode) {
        return multi(session, List.of(new Op.Create(path, data, mode))).get(0);
    }

    public Stat setData(long session, String path, String data, int expectedVersion) {
        List<Op> ops = List.of(new Op.SetData(path, data, expectedVersion));
        return locked(session, out -> {
            commit(session, ops, out);
            return nodes.get(path).stat();
        });
    }

    public void delete(long session, String path, int expectedVersion) {
        multi(session, List.of(new Op.Delete(path, expectedVersion)));
    }

    /**
     * Applies all ops atomically. Returns one path per op (the actual path for creates).
     *
     * @throws CoordException with {@code opIndex} of the failing op; nothing is changed then
     */
    public List<String> multi(long session, List<Op> ops) {
        List<Op> copy = List.copyOf(ops);
        return locked(session, out -> commit(session, copy, out));
    }

    /** Stages the ops on a copy of the tree and swaps it in only if every op succeeded. */
    private List<String> commit(long session, List<Op> ops, List<Runnable> out) {
        if (ops.isEmpty()) {
            return List.of();
        }
        Map<String, Node> staged = new HashMap<>(nodes);
        long z = zxid + 1;
        List<RawEvent> events = new ArrayList<>();
        List<String> results = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            try {
                results.add(apply(ops.get(i), session, staged, z, events));
            } catch (CoordException e) {
                throw e.withOpIndex(i);
            }
        }
        nodes = staged;
        zxid = z;
        dispatch(events, out);
        return results;
    }

    public synchronized long lastZxid() {
        return zxid;
    }

    // ---------------------------------------------------------------- internals

    private enum Kind { CREATED, DELETED, DATA, CHILDREN }

    private record RawEvent(Kind kind, String path) {}

    /** Runs {@code body} under the monitor after expiring due sessions, then runs queued callbacks outside it. */
    private <T> T locked(long session, java.util.function.Function<List<Runnable>, T> body) {
        List<Runnable> out = new ArrayList<>();
        try {
            synchronized (this) {
                expireDue(out);
                if (session >= 0 && !sessions.containsKey(session)) {
                    throw new CoordException(CoordException.Code.SESSION_EXPIRED, null, -1);
                }
                return body.apply(out);
            }
        } finally {
            for (Runnable r : out) {
                r.run();
            }
        }
    }

    private void expireDue(List<Runnable> out) {
        long now = clock.now();
        List<Map.Entry<Long, Session>> due = new ArrayList<>();
        for (var e : sessions.entrySet()) {
            if (now - e.getValue().lastBeat() >= e.getValue().timeout()) {
                due.add(e);
            }
        }
        due.sort(java.util.Comparator
                .<Map.Entry<Long, Session>>comparingLong(e -> e.getValue().lastBeat() + e.getValue().timeout())
                .thenComparing(Map.Entry::getKey));
        for (var e : due) {
            endSession(e.getKey(), out);
        }
    }

    private void endSession(long id, List<Runnable> out) {
        sessions.remove(id);
        dataWatches.values().forEach(l -> l.removeIf(w -> w.session() == id));
        childWatches.values().forEach(l -> l.removeIf(w -> w.session() == id));
        List<String> mine = new ArrayList<>();
        for (var e : nodes.entrySet()) {
            if (e.getValue().owner() == id) {
                mine.add(e.getKey());
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        mine.sort(null);
        Map<String, Node> staged = new HashMap<>(nodes);
        long z = zxid + 1;
        List<RawEvent> events = new ArrayList<>();
        for (String p : mine) {
            removeNode(p, staged, z, events);
        }
        nodes = staged;
        zxid = z;
        dispatch(events, out);
    }

    private String apply(Op op, long session, Map<String, Node> st, long z, List<RawEvent> ev) {
        switch (op) {
            case Op.Create c -> {
                validate(c.path());
                if (c.path().equals("/")) {
                    throw new CoordException(CoordException.Code.NODE_EXISTS, "/", -1);
                }
                String parentPath = parentOf(c.path());
                Node parent = st.get(parentPath);
                if (parent == null) {
                    throw new CoordException(CoordException.Code.NO_NODE, parentPath, -1);
                }
                if (parent.owner() != 0) {
                    throw new CoordException(CoordException.Code.EPHEMERAL_PARENT, parentPath, -1);
                }
                String actual = c.mode().sequential() ? c.path() + String.format("%010d", parent.cversion()) : c.path();
                if (st.containsKey(actual)) {
                    throw new CoordException(CoordException.Code.NODE_EXISTS, actual, -1);
                }
                st.put(actual, new Node(c.data(), z, z, 0, 0, c.mode().ephemeral() ? session : 0, List.of()));
                putParent(st, parentPath, parent, addSorted(parent.children(), nameOf(actual)));
                ev.add(new RawEvent(Kind.CREATED, actual));
                ev.add(new RawEvent(Kind.CHILDREN, parentPath));
                return actual;
            }
            case Op.Delete d -> {
                validate(d.path());
                if (d.path().equals("/")) {
                    throw new CoordException(CoordException.Code.BAD_PATH, "/", -1);
                }
                Node n = st.get(d.path());
                checkNode(n, d.path(), d.expectedVersion());
                if (!n.children().isEmpty()) {
                    throw new CoordException(CoordException.Code.NOT_EMPTY, d.path(), -1);
                }
                removeNode(d.path(), st, z, ev);
                return d.path();
            }
            case Op.SetData s -> {
                validate(s.path());
                Node n = st.get(s.path());
                checkNode(n, s.path(), s.expectedVersion());
                st.put(s.path(), new Node(s.data(), n.czxid(), z, n.version() + 1, n.cversion(), n.owner(), n.children()));
                ev.add(new RawEvent(Kind.DATA, s.path()));
                return s.path();
            }
            case Op.Check k -> {
                validate(k.path());
                checkNode(st.get(k.path()), k.path(), k.expectedVersion());
                return k.path();
            }
        }
    }

    private static void checkNode(Node n, String path, int expectedVersion) {
        if (n == null) {
            throw new CoordException(CoordException.Code.NO_NODE, path, -1);
        }
        if (expectedVersion != -1 && expectedVersion != n.version()) {
            throw new CoordException(CoordException.Code.BAD_VERSION, path, -1);
        }
    }

    /** Removes a childless node and records the events; parent must exist. */
    private static void removeNode(String path, Map<String, Node> st, long z, List<RawEvent> ev) {
        st.remove(path);
        String parentPath = parentOf(path);
        Node parent = st.get(parentPath);
        List<String> kids = new ArrayList<>(parent.children());
        kids.remove(nameOf(path));
        putParent(st, parentPath, parent, List.copyOf(kids));
        ev.add(new RawEvent(Kind.DELETED, path));
        ev.add(new RawEvent(Kind.CHILDREN, parentPath));
    }

    private static void putParent(Map<String, Node> st, String path, Node p, List<String> kids) {
        st.put(path, new Node(p.data(), p.czxid(), p.mzxid(), p.version(), p.cversion() + 1, p.owner(), kids));
    }

    private static List<String> addSorted(List<String> kids, String name) {
        List<String> out = new ArrayList<>(kids);
        out.add(name);
        out.sort(null);
        return List.copyOf(out);
    }

    private Node require(String path) {
        Node n = nodes.get(path);
        if (n == null) {
            throw new CoordException(CoordException.Code.NO_NODE, path, -1);
        }
        return n;
    }

    private void addWatch(Map<String, List<Watch>> table, String path, long session, Consumer<WatchEvent> fn) {
        if (fn != null) {
            table.computeIfAbsent(path, k -> new ArrayList<>()).add(new Watch(session, fn));
        }
    }

    /** Consumes matching one-shot watches (in registration order) and queues their callbacks. */
    private void dispatch(List<RawEvent> events, List<Runnable> out) {
        for (RawEvent e : events) {
            switch (e.kind()) {
                case CREATED -> fire(dataWatches, e.path(), WatchEvent.Type.NODE_CREATED, out);
                case DELETED -> {
                    fire(dataWatches, e.path(), WatchEvent.Type.NODE_DELETED, out);
                    fire(childWatches, e.path(), WatchEvent.Type.NODE_DELETED, out);
                }
                case DATA -> fire(dataWatches, e.path(), WatchEvent.Type.NODE_DATA_CHANGED, out);
                case CHILDREN -> fire(childWatches, e.path(), WatchEvent.Type.NODE_CHILDREN_CHANGED, out);
            }
        }
    }

    private static void fire(Map<String, List<Watch>> table, String path, WatchEvent.Type type, List<Runnable> out) {
        List<Watch> ws = table.remove(path);
        if (ws == null) {
            return;
        }
        WatchEvent event = new WatchEvent(type, path);
        for (Watch w : ws) {
            out.add(() -> w.fn().accept(event));
        }
    }

    // ---------------------------------------------------------------- paths

    static void validate(String path) {
        boolean ok = path != null && !path.isEmpty() && path.charAt(0) == '/' && path.indexOf('\0') < 0;
        if (ok && !path.equals("/")) {
            ok = !path.endsWith("/");
            if (ok) {
                for (String seg : path.substring(1).split("/", -1)) {
                    if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                        ok = false;
                        break;
                    }
                }
            }
        }
        if (!ok) {
            throw new CoordException(CoordException.Code.BAD_PATH, path, -1);
        }
    }

    static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i == 0 ? "/" : path.substring(0, i);
    }

    static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Test hook: verifies structural invariants, throwing {@link AssertionError} on a violation. */
    synchronized void checkInvariants() {
        for (var e : nodes.entrySet()) {
            String p = e.getKey();
            Node n = e.getValue();
            if (!p.equals("/")) {
                Node parent = nodes.get(parentOf(p));
                check(parent != null, "orphan " + p);
                check(parent.children().contains(nameOf(p)), "parent does not list " + p);
                check(parent.owner() == 0, "ephemeral parent of " + p);
            }
            check(n.owner() == 0 || sessions.containsKey(n.owner()), "dead owner on " + p);
            check(n.owner() == 0 || n.children().isEmpty(), "ephemeral with children " + p);
            check(n.cversion() >= n.children().size(), "cversion below child count " + p);
            List<String> sorted = new ArrayList<>(n.children());
            sorted.sort(null);
            check(sorted.equals(n.children()), "unsorted children " + p);
            for (String c : n.children()) {
                check(nodes.containsKey(p.equals("/") ? "/" + c : p + "/" + c), "dangling child " + c + " of " + p);
            }
            check(n.mzxid() <= zxid && n.czxid() <= n.mzxid(), "zxid order " + p);
        }
        for (var l : List.of(dataWatches, childWatches)) {
            for (var w : l.values()) {
                for (Watch x : w) {
                    check(sessions.containsKey(x.session()), "watch of dead session");
                }
            }
        }
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError(msg);
        }
    }
}
