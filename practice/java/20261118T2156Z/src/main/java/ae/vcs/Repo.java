package ae.vcs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/** A small content-addressed repository: snapshots, branches, history queries and three-way merge. */
public final class Repo {
    private final ObjectStore store;
    private final Refs refs;
    private final LongSupplier clock;

    public Repo(ObjectStore store, Refs refs, LongSupplier clock) {
        this.store = Objects.requireNonNull(store);
        this.refs = Objects.requireNonNull(refs);
        this.clock = Objects.requireNonNull(clock);
    }

    // ---- writing ----

    /** Commits the full snapshot {@code files} on {@code branch} (created if absent). Rejects empty commits. */
    public synchronized ObjectId commit(String branch, Map<String, byte[]> files, String author, String message) {
        Refs.checkName(branch);
        Objects.requireNonNull(message);
        Map<String, ObjectId> entries = new HashMap<>();
        for (var e : files.entrySet()) {
            Codec.checkPath(e.getKey());
            entries.put(e.getKey(), null); // validate every path before any write
        }
        checkNoClash(entries.keySet());
        Optional<ObjectId> parent = refs.get(branch);
        for (var e : files.entrySet()) {
            entries.put(e.getKey(), store.put(Codec.raw(Codec.BLOB, e.getValue())));
        }
        ObjectId tree = store.put(Codec.raw(Codec.TREE, Codec.encodeTree(entries)));
        if (parent.isPresent() && readCommit(parent.get()).tree().equals(tree)) {
            throw new IllegalStateException("nothing to commit");
        }
        Commit c = new Commit(tree, parent.map(List::of).orElse(List.of()), author, clock.getAsLong(), message);
        ObjectId id = store.put(Codec.raw(Codec.COMMIT, Codec.encodeCommit(c)));
        refs.set(branch, id);
        return id;
    }

    /** Creates a new branch pointing at an existing commit. */
    public synchronized void branch(String name, ObjectId at) {
        Refs.checkName(name);
        if (refs.get(name).isPresent()) {
            throw new IllegalStateException("branch exists: " + name);
        }
        readCommit(at);
        refs.set(name, at);
    }

    public Optional<ObjectId> head(String branch) {
        return refs.get(branch);
    }

    // ---- reading ----

    public Commit readCommit(ObjectId id) {
        Codec.Decoded d = load(id);
        if (!d.kind().equals(Codec.COMMIT)) {
            throw new CorruptObjectException(id + " is a " + d.kind() + ", not a commit");
        }
        return Codec.decodeCommit(d.payload());
    }

    /** The snapshot of a commit as path to bytes, in path order. */
    public Map<String, byte[]> checkout(ObjectId commit) {
        Map<String, byte[]> out = new TreeMap<>(Codec.PATH_ORDER);
        for (var e : readTree(readCommit(commit).tree()).entrySet()) {
            Codec.Decoded blob = load(e.getValue());
            if (!blob.kind().equals(Codec.BLOB)) {
                throw new CorruptObjectException(e.getValue() + " is not a blob");
            }
            out.put(e.getKey(), blob.payload());
        }
        return out;
    }

    private Codec.Decoded load(ObjectId id) {
        byte[] raw = store.get(id).orElseThrow(() -> new CorruptObjectException("missing object " + id));
        return Codec.decode(raw);
    }

    private TreeMap<String, ObjectId> readTree(ObjectId id) {
        Codec.Decoded d = load(id);
        if (!d.kind().equals(Codec.TREE)) {
            throw new CorruptObjectException(id + " is a " + d.kind() + ", not a tree");
        }
        return Codec.decodeTree(d.payload());
    }

    // ---- history ----

    /** Every commit reachable from {@code from}, children before parents; ties: newer time, then smaller id. */
    public List<ObjectId> log(ObjectId from) {
        Map<ObjectId, Commit> commits = reachable(from);
        Map<ObjectId, Integer> pendingChildren = new HashMap<>();
        commits.keySet().forEach(id -> pendingChildren.put(id, 0));
        for (Commit c : commits.values()) {
            for (ObjectId p : new HashSet<>(c.parents())) {
                pendingChildren.merge(p, 1, Integer::sum);
            }
        }
        Comparator<ObjectId> order = Comparator.<ObjectId>comparingLong(id -> commits.get(id).time()).reversed()
            .thenComparing(Comparator.naturalOrder());
        PriorityQueue<ObjectId> ready = new PriorityQueue<>(order);
        pendingChildren.forEach((id, n) -> {
            if (n == 0) {
                ready.add(id);
            }
        });
        List<ObjectId> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            ObjectId id = ready.poll();
            out.add(id);
            for (ObjectId p : new HashSet<>(commits.get(id).parents())) {
                if (pendingChildren.merge(p, -1, Integer::sum) == 0) {
                    ready.add(p);
                }
            }
        }
        return out;
    }

    private Map<ObjectId, Commit> reachable(ObjectId from) {
        Map<ObjectId, Commit> seen = new HashMap<>();
        ArrayDeque<ObjectId> todo = new ArrayDeque<>();
        todo.add(from);
        while (!todo.isEmpty()) {
            ObjectId id = todo.poll();
            if (seen.containsKey(id)) {
                continue;
            }
            Commit c = readCommit(id);
            seen.put(id, c);
            todo.addAll(c.parents());
        }
        return seen;
    }

    /** True when {@code ancestor} is {@code descendant} or reachable from it. */
    public boolean isAncestor(ObjectId ancestor, ObjectId descendant) {
        return reachable(descendant).containsKey(ancestor);
    }

    /**
     * Best common ancestor: a common ancestor that is not an ancestor of another common ancestor.
     * If several exist (criss-cross merges) the newest, then the smallest id, is returned.
     */
    public Optional<ObjectId> mergeBase(ObjectId a, ObjectId b) {
        Map<ObjectId, Commit> ca = reachable(a);
        Map<ObjectId, Commit> cb = reachable(b);
        Set<ObjectId> common = new HashSet<>(ca.keySet());
        common.retainAll(cb.keySet());
        Set<ObjectId> covered = new HashSet<>();
        for (ObjectId c : common) {
            for (ObjectId p : ca.get(c).parents()) {
                covered.addAll(reachable(p).keySet());
            }
        }
        return common.stream().filter(c -> !covered.contains(c))
            .min(Comparator.<ObjectId>comparingLong(c -> -ca.get(c).time()).thenComparing(Comparator.naturalOrder()));
    }

    /** Path changes going from commit {@code a} to commit {@code b}, sorted by path. */
    public List<Change> diff(ObjectId a, ObjectId b) {
        Map<String, ObjectId> ta = readTree(readCommit(a).tree());
        Map<String, ObjectId> tb = readTree(readCommit(b).tree());
        TreeSet<String> paths = new TreeSet<>(Codec.PATH_ORDER);
        paths.addAll(ta.keySet());
        paths.addAll(tb.keySet());
        List<Change> out = new ArrayList<>();
        for (String p : paths) {
            ObjectId x = ta.get(p);
            ObjectId y = tb.get(p);
            if (x == null) {
                out.add(new Change(p, Change.Kind.ADDED));
            } else if (y == null) {
                out.add(new Change(p, Change.Kind.DELETED));
            } else if (!x.equals(y)) {
                out.add(new Change(p, Change.Kind.MODIFIED));
            }
        }
        return out;
    }

    // ---- merge ----

    /** Merges branch {@code from} into branch {@code into}. Conflicts write nothing and move no ref. */
    public synchronized MergeResult merge(String into, String from, String author, String message) {
        ObjectId ours = refs.get(into).orElseThrow(() -> new IllegalArgumentException("no such branch: " + into));
        ObjectId theirs = refs.get(from).orElseThrow(() -> new IllegalArgumentException("no such branch: " + from));
        if (isAncestor(theirs, ours)) {
            return new MergeResult.UpToDate();
        }
        if (isAncestor(ours, theirs)) {
            refs.set(into, theirs);
            return new MergeResult.FastForward(theirs);
        }
        Map<String, ObjectId> base = mergeBase(ours, theirs).map(b -> readTree(readCommit(b).tree())).orElse(new TreeMap<>());
        Map<String, ObjectId> mine = readTree(readCommit(ours).tree());
        Map<String, ObjectId> other = readTree(readCommit(theirs).tree());

        TreeSet<String> paths = new TreeSet<>(Codec.PATH_ORDER);
        paths.addAll(base.keySet());
        paths.addAll(mine.keySet());
        paths.addAll(other.keySet());
        Map<String, ObjectId> merged = new TreeMap<>(Codec.PATH_ORDER);
        Map<String, Conflict.Kind> conflicts = new TreeMap<>(Codec.PATH_ORDER);
        for (String p : paths) {
            ObjectId b = base.get(p);
            ObjectId m = mine.get(p);
            ObjectId t = other.get(p);
            ObjectId pick;
            if (Objects.equals(m, t)) {
                pick = m;
            } else if (Objects.equals(m, b)) {
                pick = t;
            } else if (Objects.equals(t, b)) {
                pick = m;
            } else {
                conflicts.put(p, m == null || t == null ? Conflict.Kind.CHANGED_DELETED : Conflict.Kind.BOTH_CHANGED);
                continue;
            }
            if (pick != null) {
                merged.put(p, pick);
            }
        }
        // A merged result may hold "a" (file) and "a/b" (file) even though each side alone was valid.
        for (String dir = Codec.findFileDirectoryClash(merged.keySet()); dir != null;
             dir = Codec.findFileDirectoryClash(merged.keySet())) {
            conflicts.put(dir, Conflict.Kind.FILE_DIRECTORY);
            final String d = dir;
            merged.keySet().removeIf(k -> k.equals(d) || k.startsWith(d + "/"));
            // paths under the directory are dropped from the clash search only; the merge is aborted anyway
        }
        if (!conflicts.isEmpty()) {
            List<Conflict> list = new ArrayList<>();
            conflicts.forEach((path, kind) -> list.add(new Conflict(path, kind)));
            return new MergeResult.Conflicted(list);
        }
        ObjectId tree = store.put(Codec.raw(Codec.TREE, Codec.encodeTree(merged)));
        Commit c = new Commit(tree, List.of(ours, theirs), author, clock.getAsLong(), message);
        ObjectId id = store.put(Codec.raw(Codec.COMMIT, Codec.encodeCommit(c)));
        refs.set(into, id);
        return new MergeResult.Merged(id);
    }

    private static void checkNoClash(Set<String> paths) {
        String clash = Codec.findFileDirectoryClash(paths);
        if (clash != null) {
            throw new IllegalArgumentException("path is both a file and a directory: " + clash);
        }
    }

    // ---- integrity ----

    /** Walks every branch and reports missing or corrupt objects as sorted, human-readable problems. */
    public List<String> fsck() {
        TreeSet<String> problems = new TreeSet<>();
        Set<ObjectId> checked = new HashSet<>();
        ArrayDeque<ObjectId> todo = new ArrayDeque<>();
        for (String name : refs.names()) {
            todo.add(refs.get(name).orElseThrow());
        }
        while (!todo.isEmpty()) {
            ObjectId id = todo.poll();
            if (!checked.add(id)) {
                continue;
            }
            Commit c;
            try {
                c = readCommit(id);
            } catch (CorruptObjectException e) {
                problems.add("commit " + id + ": " + e.getMessage());
                continue;
            }
            todo.addAll(c.parents());
            try {
                for (var e : readTree(c.tree()).entrySet()) {
                    if (checked.add(e.getValue())) {
                        try {
                            if (!load(e.getValue()).kind().equals(Codec.BLOB)) {
                                problems.add("blob " + e.getValue() + ": wrong kind");
                            }
                        } catch (CorruptObjectException ex) {
                            problems.add("blob " + e.getValue() + ": " + ex.getMessage());
                        }
                    }
                }
            } catch (CorruptObjectException e) {
                problems.add("tree " + c.tree() + ": " + e.getMessage());
            }
        }
        return List.copyOf(problems);
    }

    /** UTF-8 helper for tests and callers. */
    public static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
