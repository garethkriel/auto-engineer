package ae.merkle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * String-to-String map stored as a depth-4, 16-ary hash trie keyed by the SHA-256 of the key. The root hash depends
 * only on the content. Not thread-safe.
 */
public final class MerkleMap implements Remote {
    private abstract static class Node {
        byte[] hash;
        int count;
    }

    private static final class Leaf extends Node {
        final TreeMap<String, String> entries = new TreeMap<>();
    }

    private static final class Inner extends Node {
        final Node[] kids = new Node[Hashing.FANOUT];
    }

    private Inner root = newInner(0);

    private static Inner newInner(int level) {
        Inner n = new Inner();
        n.hash = Hashing.EMPTY[level];
        return n;
    }

    /** Stores a value; returns the previous one, if any. */
    public Optional<String> put(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        String path = Hashing.pathOf(key);
        Inner node = root;
        List<Inner> trail = new ArrayList<>();
        for (int level = 0; level < Hashing.DEPTH - 1; level++) {
            trail.add(node);
            int d = digit(path, level);
            if (node.kids[d] == null) {
                node.kids[d] = newInner(level + 1);
            }
            node = (Inner) node.kids[d];
        }
        trail.add(node);
        int last = digit(path, Hashing.DEPTH - 1);
        Leaf leaf = (Leaf) node.kids[last];
        if (leaf == null) {
            leaf = new Leaf();
            node.kids[last] = leaf;
        }
        String prev = leaf.entries.put(key, value);
        leaf.count = leaf.entries.size();
        leaf.hash = Hashing.leafHash(leaf.entries);
        for (int i = trail.size() - 1; i >= 0; i--) {
            refresh(trail.get(i), i);
        }
        return Optional.ofNullable(prev);
    }

    /** Removes a key; true if it was present. */
    public boolean remove(String key) {
        Objects.requireNonNull(key, "key");
        String path = Hashing.pathOf(key);
        List<Inner> trail = new ArrayList<>();
        Inner node = root;
        for (int level = 0; level < Hashing.DEPTH - 1; level++) {
            trail.add(node);
            Node next = node.kids[digit(path, level)];
            if (next == null) {
                return false;
            }
            node = (Inner) next;
        }
        trail.add(node);
        int last = digit(path, Hashing.DEPTH - 1);
        Leaf leaf = (Leaf) node.kids[last];
        if (leaf == null || leaf.entries.remove(key) == null) {
            return false;
        }
        if (leaf.entries.isEmpty()) {
            node.kids[last] = null;
        } else {
            leaf.count = leaf.entries.size();
            leaf.hash = Hashing.leafHash(leaf.entries);
        }
        for (int i = trail.size() - 1; i >= 0; i--) {
            Inner n = trail.get(i);
            refresh(n, i);
            if (i > 0 && n.count == 0) {
                trail.get(i - 1).kids[digit(path, i - 1)] = null;
            }
        }
        return true;
    }

    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        Leaf leaf = leafAt(Hashing.pathOf(key));
        return leaf == null ? Optional.empty() : Optional.ofNullable(leaf.entries.get(key));
    }

    public int size() {
        return root.count;
    }

    public byte[] rootHash() {
        return root.hash.clone();
    }

    /** Snapshot of all entries in key order. */
    public SortedMap<String, String> entries() {
        TreeMap<String, String> out = new TreeMap<>();
        collect(root, out);
        return out;
    }

    public MerkleMap copy() {
        MerkleMap c = new MerkleMap();
        entries().forEach(c::put);
        return c;
    }

    @Override
    public byte[] hashAt(String path) {
        checkPath(path, Hashing.DEPTH);
        Node n = nodeAt(path);
        return n == null ? Hashing.EMPTY[path.length()].clone() : n.hash.clone();
    }

    @Override
    public byte[][] childHashes(String path) {
        checkPath(path, Hashing.DEPTH - 1);
        Node n = nodeAt(path);
        byte[][] out = new byte[Hashing.FANOUT][];
        for (int i = 0; i < Hashing.FANOUT; i++) {
            Node kid = n == null ? null : ((Inner) n).kids[i];
            out[i] = kid == null ? Hashing.EMPTY[path.length() + 1].clone() : kid.hash.clone();
        }
        return out;
    }

    /** Entries of one bucket (path of exactly 4 nibbles) in key order. */
    public SortedMap<String, String> bucketEntries(String path) {
        if (path.length() != Hashing.DEPTH) {
            throw new IllegalArgumentException("bucket path needs " + Hashing.DEPTH + " nibbles");
        }
        checkPath(path, Hashing.DEPTH);
        Leaf leaf = leafAt(path);
        return leaf == null ? new TreeMap<>() : new TreeMap<>(leaf.entries);
    }

    @Override
    public List<Proof.Entry> bucket(String path) {
        List<Proof.Entry> out = new ArrayList<>();
        bucketEntries(path).forEach((k, v) -> out.add(new Proof.Entry(k, v)));
        return out;
    }

    /** Builds an inclusion (key present) or non-inclusion (key absent) proof against the current root. */
    public Proof prove(String key) {
        Objects.requireNonNull(key, "key");
        String path = Hashing.pathOf(key);
        List<List<byte[]>> siblings = new ArrayList<>();
        for (int level = 0; level < Hashing.DEPTH; level++) {
            byte[][] kids = childHashes(path.substring(0, level));
            int d = digit(path, level);
            List<byte[]> row = new ArrayList<>();
            for (int i = 0; i < Hashing.FANOUT; i++) {
                if (i != d) {
                    row.add(kids[i]);
                }
            }
            siblings.add(row);
        }
        return new Proof(bucket(path), siblings);
    }

    /**
     * Checks a proof that {@code key} maps to {@code value} (or is absent when empty) under {@code root}.
     * Never throws on malformed proofs; returns false.
     */
    public static boolean verify(byte[] root, String key, Optional<String> value, Proof proof) {
        if (root == null || root.length != Hashing.HASH_BYTES || key == null || value == null || proof == null
                || proof.bucket() == null || proof.siblings() == null
                || proof.siblings().size() != Hashing.DEPTH) {
            return false;
        }
        String path = Hashing.pathOf(key);
        TreeMap<String, String> bucket = new TreeMap<>();
        String previous = null;
        for (Proof.Entry e : proof.bucket()) {
            if (e == null || e.key() == null || e.value() == null) {
                return false;
            }
            if (previous != null && previous.compareTo(e.key()) >= 0) {
                return false; // must be strictly ascending, which also rules out duplicates
            }
            if (!Hashing.pathOf(e.key()).equals(path)) {
                return false;
            }
            bucket.put(e.key(), e.value());
            previous = e.key();
        }
        if (!Objects.equals(Optional.ofNullable(bucket.get(key)), value)) {
            return false;
        }
        byte[] h = Hashing.leafHash(bucket);
        for (int level = Hashing.DEPTH - 1; level >= 0; level--) {
            List<byte[]> row = proof.siblings().get(level);
            if (row == null || row.size() != Hashing.FANOUT - 1) {
                return false;
            }
            int d = digit(path, level);
            byte[][] kids = new byte[Hashing.FANOUT][];
            int si = 0;
            for (int i = 0; i < Hashing.FANOUT; i++) {
                if (i == d) {
                    kids[i] = h;
                } else {
                    byte[] s = row.get(si++);
                    if (s == null || s.length != Hashing.HASH_BYTES) {
                        return false;
                    }
                    kids[i] = s;
                }
            }
            h = Hashing.innerHash(kids);
        }
        return Arrays.equals(h, root);
    }

    /** Throws IllegalStateException("invariant: ...") if the structure is inconsistent. */
    public void checkInvariants() {
        check(root, 0, "");
    }

    private int check(Node n, int level, String path) {
        if (n instanceof Leaf leaf) {
            if (leaf.entries.isEmpty()) {
                throw new IllegalStateException("invariant: empty node at " + path);
            }
            for (String k : leaf.entries.keySet()) {
                if (!Hashing.pathOf(k).equals(path)) {
                    throw new IllegalStateException("invariant: key " + k + " in wrong bucket " + path);
                }
            }
            if (leaf.count != leaf.entries.size()) {
                throw new IllegalStateException("invariant: count mismatch at " + path);
            }
            if (!Arrays.equals(leaf.hash, Hashing.leafHash(leaf.entries))) {
                throw new IllegalStateException("invariant: stale hash at " + path);
            }
            return leaf.count;
        }
        Inner inner = (Inner) n;
        int total = 0;
        byte[][] kids = new byte[Hashing.FANOUT][];
        for (int i = 0; i < Hashing.FANOUT; i++) {
            Node kid = inner.kids[i];
            if (kid == null) {
                kids[i] = Hashing.EMPTY[level + 1];
            } else {
                String childPath = path + Character.forDigit(i, 16);
                if (kid.count == 0) {
                    throw new IllegalStateException("invariant: empty node at " + childPath);
                }
                total += check(kid, level + 1, childPath);
                kids[i] = kid.hash;
            }
        }
        if (total != inner.count) {
            throw new IllegalStateException("invariant: count mismatch at '" + path + "'");
        }
        if (!Arrays.equals(inner.hash, Hashing.innerHash(kids))) {
            throw new IllegalStateException("invariant: stale hash at '" + path + "'");
        }
        return total;
    }

    /** Number of allocated nodes including the root (testing aid). */
    int nodeCount() {
        return count(root);
    }

    private static int count(Node n) {
        if (n instanceof Inner inner) {
            int c = 1;
            for (Node k : inner.kids) {
                if (k != null) {
                    c += count(k);
                }
            }
            return c;
        }
        return 1;
    }

    // ---- corruption hooks for checker tests ----

    void corruptCachedHashForTest() {
        Node n = root;
        while (n instanceof Inner inner) {
            Node next = null;
            for (Node k : inner.kids) {
                if (k != null) {
                    next = k;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            n = next;
        }
        n.hash = Hashing.EMPTY[0].clone(); // a leaf whose cached hash no longer matches its entries
    }

    void corruptCountForTest() {
        root.count += 1;
    }

    void leaveEmptyChildForTest() {
        Inner n = root;
        while (true) {
            for (int i = 0; i < Hashing.FANOUT; i++) {
                if (n.kids[i] instanceof Inner in) {
                    n = in;
                    break;
                }
                if (n.kids[i] instanceof Leaf leaf) {
                    leaf.entries.clear();
                    return;
                }
            }
        }
    }

    void corruptInnerHashForTest() {
        Node n = root;
        while (n instanceof Inner inner) {
            for (Node k : inner.kids) {
                if (k instanceof Inner) {
                    n = k;
                    break;
                }
            }
            if (n == inner) {
                break;
            }
        }
        n.hash = Hashing.EMPTY[4].clone(); // an inner node (deepest one) with a stale cached hash
    }

    void corruptLeafCountForTest() {
        forEachLeaf(root, leaf -> leaf.count += 1);
        // keep every ancestor count consistent with its (now wrong) children: only the leaf rule must fire
        fixCounts(root);
    }

    void plantForeignKeyForTest(String key) {
        forEachLeaf(root, leaf -> {
            leaf.entries.put(key, "x");
            leaf.count = leaf.entries.size();
            leaf.hash = Hashing.leafHash(leaf.entries);
        });
        refreshAllForTest(root, 0);
    }

    private static void forEachLeaf(Node n, java.util.function.Consumer<Leaf> f) {
        if (n instanceof Leaf leaf) {
            f.accept(leaf);
        } else {
            for (Node k : ((Inner) n).kids) {
                if (k != null) {
                    forEachLeaf(k, f);
                }
            }
        }
    }

    private static int fixCounts(Node n) {
        if (n instanceof Leaf leaf) {
            return leaf.entries.size();
        }
        Inner inner = (Inner) n;
        int t = 0;
        for (Node k : inner.kids) {
            if (k != null) {
                t += k instanceof Leaf l ? l.count : fixCounts(k);
            }
        }
        inner.count = t;
        return t;
    }

    private static void refreshAllForTest(Node n, int level) {
        if (n instanceof Inner inner) {
            for (Node k : inner.kids) {
                if (k != null) {
                    refreshAllForTest(k, level + 1);
                }
            }
            refresh(inner, level);
        }
    }

    // ---- helpers ----

    private static void refresh(Inner n, int level) {
        byte[][] kids = new byte[Hashing.FANOUT][];
        int total = 0;
        for (int i = 0; i < Hashing.FANOUT; i++) {
            Node k = n.kids[i];
            kids[i] = k == null ? Hashing.EMPTY[level + 1] : k.hash;
            total += k == null ? 0 : k.count;
        }
        n.count = total;
        n.hash = Hashing.innerHash(kids);
    }

    private static int digit(String path, int level) {
        return Character.digit(path.charAt(level), 16);
    }

    private static void checkPath(String path, int maxLen) {
        Objects.requireNonNull(path, "path");
        if (path.length() > maxLen) {
            throw new IllegalArgumentException("path too long: " + path);
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new IllegalArgumentException("bad nibble in path: " + path);
            }
        }
    }

    private Node nodeAt(String path) {
        Node n = root;
        for (int i = 0; i < path.length() && n != null; i++) {
            n = ((Inner) n).kids[digit(path, i)];
        }
        return n;
    }

    private Leaf leafAt(String path) {
        return (Leaf) nodeAt(path);
    }

    private static void collect(Node n, Map<String, String> out) {
        if (n instanceof Leaf leaf) {
            out.putAll(leaf.entries);
        } else {
            for (Node k : ((Inner) n).kids) {
                if (k != null) {
                    collect(k, out);
                }
            }
        }
    }
}
