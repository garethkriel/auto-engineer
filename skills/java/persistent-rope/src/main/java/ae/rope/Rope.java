package ae.rope;

import java.util.Objects;

/**
 * An immutable, persistent text rope: a height-balanced (AVL-style) binary tree whose leaves are short strings.
 *
 * <p>Concatenation, splitting, insertion, deletion and indexing are O(log n); every operation returns a new rope that
 * shares unchanged subtrees with its inputs, so old versions stay valid. Each node caches its length, newline count and
 * height, which gives a line index for free. Only {@code '\n'} terminates a line.
 */
public final class Rope implements CharSequence {

    /** Maximum number of characters in one leaf. Package-private so tests can cross leaf boundaries cheaply. */
    static final int LEAF_MAX = 32;

    private static final Node EMPTY_LEAF = new Leaf("");
    private static final Rope EMPTY = new Rope(EMPTY_LEAF);

    /** A text position: 0-based line and column. */
    public record Position(int line, int column) { }

    /** The two halves produced by {@link #split(int)}. */
    public record Split(Rope left, Rope right) { }

    // ---------------------------------------------------------------- nodes

    abstract static class Node {
        final int len;
        final int nl;
        final int height;

        Node(int len, int nl, int height) {
            this.len = len;
            this.nl = nl;
            this.height = height;
        }
    }

    static final class Leaf extends Node {
        final String s;

        Leaf(String s) {
            super(s.length(), countNewlines(s, 0, s.length()), 0);
            this.s = s;
        }
    }

    static final class Branch extends Node {
        final Node left;
        final Node right;

        Branch(Node left, Node right) {
            super(Math.addExact(left.len, right.len), left.nl + right.nl, 1 + Math.max(left.height, right.height));
            this.left = left;
            this.right = right;
        }
    }

    private static int countNewlines(String s, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- tree algorithms

    /** Builds a balanced tree of full leaves from {@code s[lo, hi)}. */
    private static Node build(CharSequence s, int lo, int hi) {
        int n = hi - lo;
        if (n == 0) {
            return EMPTY_LEAF;
        }
        if (n <= LEAF_MAX) {
            return new Leaf(s.subSequence(lo, hi).toString());
        }
        int chunks = (n + LEAF_MAX - 1) / LEAF_MAX;
        int mid = lo + ((chunks + 1) / 2) * LEAF_MAX;
        return new Branch(build(s, lo, mid), build(s, mid, hi));
    }

    private static Node balance(Node l, Node r) {
        if (l.height > r.height + 1) {
            Branch lb = (Branch) l;
            if (lb.left.height >= lb.right.height) {
                return new Branch(lb.left, new Branch(lb.right, r));
            }
            Branch lr = (Branch) lb.right;
            return new Branch(new Branch(lb.left, lr.left), new Branch(lr.right, r));
        }
        if (r.height > l.height + 1) {
            Branch rb = (Branch) r;
            if (rb.right.height >= rb.left.height) {
                return new Branch(new Branch(l, rb.left), rb.right);
            }
            Branch rl = (Branch) rb.left;
            return new Branch(new Branch(l, rl.left), new Branch(rl.right, rb.right));
        }
        return new Branch(l, r);
    }

    private static boolean fits(Node a, Node b) {
        return a instanceof Leaf && b instanceof Leaf && a.len + b.len <= LEAF_MAX;
    }

    private static Node merge(Node a, Node b) {
        return new Leaf(((Leaf) a).s + ((Leaf) b).s);
    }

    /** Concatenates two balanced trees into one balanced tree. */
    private static Node join(Node a, Node b) {
        if (a.len == 0) {
            return b;
        }
        if (b.len == 0) {
            return a;
        }
        if (fits(a, b)) {
            return merge(a, b);
        }
        if (a.height > b.height + 1) {
            Branch x = (Branch) a;
            return balance(x.left, join(x.right, b));
        }
        if (b.height > a.height + 1) {
            Branch y = (Branch) b;
            return balance(join(a, y.left), y.right);
        }
        // heights differ by at most one: keep leaves dense by merging into an edge leaf when it fits
        if (a instanceof Branch x && fits(x.right, b)) {
            return new Branch(x.left, merge(x.right, b));
        }
        if (b instanceof Branch y && fits(a, y.left)) {
            return new Branch(merge(a, y.left), y.right);
        }
        return new Branch(a, b);
    }

    private static Node[] splitNode(Node n, int i) {
        if (i == 0) {
            return new Node[] {EMPTY_LEAF, n};
        }
        if (i == n.len) {
            return new Node[] {n, EMPTY_LEAF};
        }
        if (n instanceof Leaf leaf) {
            return new Node[] {new Leaf(leaf.s.substring(0, i)), new Leaf(leaf.s.substring(i))};
        }
        Branch b = (Branch) n;
        if (i <= b.left.len) {
            Node[] p = splitNode(b.left, i);
            return new Node[] {p[0], join(p[1], b.right)};
        }
        Node[] p = splitNode(b.right, i - b.left.len);
        return new Node[] {join(b.left, p[0]), p[1]};
    }

    private static void appendRange(Node n, int from, int to, StringBuilder out) {
        if (from >= to) {
            return;
        }
        if (n instanceof Leaf leaf) {
            out.append(leaf.s, from, to);
            return;
        }
        Branch b = (Branch) n;
        int ll = b.left.len;
        if (from < ll) {
            appendRange(b.left, from, Math.min(to, ll), out);
        }
        if (to > ll) {
            appendRange(b.right, Math.max(from, ll) - ll, to - ll, out);
        }
    }

    /** Offset of the k-th newline (1-based) in the subtree. */
    private static int offsetOfNewline(Node n, int k) {
        if (n instanceof Leaf leaf) {
            int seen = 0;
            for (int i = 0; i < leaf.s.length(); i++) {
                if (leaf.s.charAt(i) == '\n' && ++seen == k) {
                    return i;
                }
            }
            throw new IllegalStateException("newline count out of sync");
        }
        Branch b = (Branch) n;
        if (k <= b.left.nl) {
            return offsetOfNewline(b.left, k);
        }
        return b.left.len + offsetOfNewline(b.right, k - b.left.nl);
    }

    /** Number of newlines in the first {@code offset} characters of the subtree. */
    private static int newlinesBefore(Node n, int offset) {
        if (offset == 0) {
            return 0;
        }
        if (offset == n.len) {
            return n.nl;
        }
        if (n instanceof Leaf leaf) {
            return countNewlines(leaf.s, 0, offset);
        }
        Branch b = (Branch) n;
        if (offset <= b.left.len) {
            return newlinesBefore(b.left, offset);
        }
        return b.left.nl + newlinesBefore(b.right, offset - b.left.len);
    }

    // ---------------------------------------------------------------- rope

    private final Node root;

    /** Wraps a tree as-is (no balancing); package-private so tests can build corrupt trees for {@link #check()}. */
    Rope(Node root) {
        this.root = root;
    }

    /** The empty rope. */
    public static Rope empty() {
        return EMPTY;
    }

    /** A rope holding the characters of {@code text}. */
    public static Rope of(CharSequence text) {
        Objects.requireNonNull(text, "text");
        CharSequence flat = text instanceof Rope ? text.toString() : text;
        return flat.length() == 0 ? EMPTY : new Rope(build(flat, 0, flat.length()));
    }

    @Override
    public int length() {
        return root.len;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= root.len) {
            throw new IndexOutOfBoundsException("index " + index + ", length " + root.len);
        }
        Node n = root;
        int i = index;
        while (n instanceof Branch b) {
            if (i < b.left.len) {
                n = b.left;
            } else {
                i -= b.left.len;
                n = b.right;
            }
        }
        return ((Leaf) n).s.charAt(i);
    }

    private void checkRange(int from, int to) {
        if (from < 0 || to > root.len || from > to) {
            throw new IndexOutOfBoundsException("range [" + from + ", " + to + ") of length " + root.len);
        }
    }

    private void checkOffset(int at) {
        if (at < 0 || at > root.len) {
            throw new IndexOutOfBoundsException("offset " + at + ", length " + root.len);
        }
    }

    /** The characters in {@code [from, to)} as a String. */
    public String substring(int from, int to) {
        checkRange(from, to);
        StringBuilder sb = new StringBuilder(to - from);
        appendRange(root, from, to, sb);
        return sb.toString();
    }

    @Override
    public Rope subSequence(int from, int to) {
        checkRange(from, to);
        Node[] right = splitNode(root, to);
        return new Rope(splitNode(right[0], from)[1]);
    }

    /** This rope followed by {@code other}. */
    public Rope concat(Rope other) {
        Objects.requireNonNull(other, "other");
        return new Rope(join(root, other.root));
    }

    /** Splits into {@code [0, at)} and {@code [at, length)}. */
    public Split split(int at) {
        checkOffset(at);
        Node[] p = splitNode(root, at);
        return new Split(new Rope(p[0]), new Rope(p[1]));
    }

    /** Inserts {@code text} before offset {@code at}. */
    public Rope insert(int at, CharSequence text) {
        Objects.requireNonNull(text, "text");
        checkOffset(at);
        return replace(at, at, text);
    }

    /** Removes {@code [from, to)}. */
    public Rope delete(int from, int to) {
        checkRange(from, to);
        return replace(from, to, "");
    }

    /** Replaces {@code [from, to)} with {@code text}. */
    public Rope replace(int from, int to, CharSequence text) {
        Objects.requireNonNull(text, "text");
        checkRange(from, to);
        Node mid = text.length() == 0 ? EMPTY_LEAF : of(text).root;
        Node[] tail = splitNode(root, to);
        Node[] head = splitNode(tail[0], from);
        return new Rope(join(join(head[0], mid), tail[1]));
    }

    // ---------------------------------------------------------------- lines

    /** Number of lines: newlines + 1 (an empty rope has one empty line). */
    public int lineCount() {
        return root.nl + 1;
    }

    /** Offset of the first character of {@code line}. */
    public int lineStart(int line) {
        if (line < 0 || line >= lineCount()) {
            throw new IndexOutOfBoundsException("line " + line + ", line count " + lineCount());
        }
        return line == 0 ? 0 : offsetOfNewline(root, line) + 1;
    }

    /** Line containing {@code offset}; {@code offset == length} is the end of the last line. */
    public int lineOf(int offset) {
        checkOffset(offset);
        return newlinesBefore(root, offset);
    }

    /** Text of {@code line} without its terminating newline. */
    public String line(int line) {
        int start = lineStart(line);
        int end = line + 1 < lineCount() ? lineStart(line + 1) - 1 : root.len;
        return substring(start, end);
    }

    /** Line and column of {@code offset}. */
    public Position positionOf(int offset) {
        int line = lineOf(offset);
        return new Position(line, offset - lineStart(line));
    }

    /** Offset of {@code (line, column)}; column may equal the line length. */
    public int offsetOf(int line, int column) {
        int start = lineStart(line);
        int end = line + 1 < lineCount() ? lineStart(line + 1) - 1 : root.len;
        if (column < 0 || column > end - start) {
            throw new IndexOutOfBoundsException("column " + column + " of line " + line + " (length " + (end - start) + ")");
        }
        return start + column;
    }

    // ---------------------------------------------------------------- search

    /** Index of the first occurrence of {@code needle}, or -1. */
    public int indexOf(String needle) {
        return indexOf(needle, 0);
    }

    /** Index of the first occurrence of {@code needle} at or after {@code from}, or -1 (KMP, no copying). */
    public int indexOf(String needle, int from) {
        Objects.requireNonNull(needle, "needle");
        int start = Math.max(from, 0);
        if (needle.isEmpty()) {
            return Math.min(start, root.len);
        }
        Kmp kmp = new Kmp(needle);
        return scan(root, 0, start, kmp) ? kmp.found : -1;
    }

    private static final class Kmp {
        final String p;
        final int[] fail;
        int matched;
        int found = -1;

        Kmp(String p) {
            this.p = p;
            this.fail = new int[p.length()];
            int k = 0;
            for (int i = 1; i < p.length(); i++) {
                while (k > 0 && p.charAt(i) != p.charAt(k)) {
                    k = fail[k - 1];
                }
                if (p.charAt(i) == p.charAt(k)) {
                    k++;
                }
                fail[i] = k;
            }
        }

        boolean feed(char c, int pos) {
            while (matched > 0 && p.charAt(matched) != c) {
                matched = fail[matched - 1];
            }
            if (p.charAt(matched) == c) {
                matched++;
            }
            if (matched == p.length()) {
                found = pos - p.length() + 1;
                return true;
            }
            return false;
        }
    }

    private static boolean scan(Node n, int base, int from, Kmp kmp) {
        if (base + n.len <= from) {
            return false;
        }
        if (n instanceof Leaf leaf) {
            for (int i = Math.max(0, from - base); i < leaf.s.length(); i++) {
                if (kmp.feed(leaf.s.charAt(i), base + i)) {
                    return true;
                }
            }
            return false;
        }
        Branch b = (Branch) n;
        return scan(b.left, base, from, kmp) || scan(b.right, base + b.left.len, from, kmp);
    }

    // ---------------------------------------------------------------- object + diagnostics

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(root.len);
        appendRange(root, 0, root.len, sb);
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rope other) || other.root.len != root.len) {
            return false;
        }
        return toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    /** Height of the tree (a single leaf has depth 0). */
    int depth() {
        return root.height;
    }

    /** Number of leaves (an empty rope has one empty leaf). */
    int leafCount() {
        return countLeaves(root);
    }

    private static int countLeaves(Node n) {
        return n instanceof Branch b ? countLeaves(b.left) + countLeaves(b.right) : 1;
    }

    /** Verifies every structural invariant; throws {@link IllegalStateException} on the first violation. */
    void check() {
        checkNode(root, true);
    }

    private static void checkNode(Node n, boolean isRoot) {
        if (n instanceof Leaf leaf) {
            if (leaf.len != leaf.s.length() || leaf.nl != countNewlines(leaf.s, 0, leaf.s.length()) || leaf.height != 0) {
                throw new IllegalStateException("bad leaf cache");
            }
            if (leaf.len > LEAF_MAX) {
                throw new IllegalStateException("leaf too long: " + leaf.len);
            }
            if (leaf.len == 0 && !isRoot) {
                throw new IllegalStateException("empty non-root leaf");
            }
            return;
        }
        Branch b = (Branch) n;
        checkNode(b.left, false);
        checkNode(b.right, false);
        if (b.len != b.left.len + b.right.len || b.nl != b.left.nl + b.right.nl
                || b.height != 1 + Math.max(b.left.height, b.right.height)) {
            throw new IllegalStateException("bad branch cache");
        }
        if (Math.abs(b.left.height - b.right.height) > 1) {
            throw new IllegalStateException("unbalanced: " + b.left.height + " vs " + b.right.height);
        }
    }
}
