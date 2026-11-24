package ae.rbt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Sorted map backed by a left-leaning red-black tree (Sedgewick's 2-3 variant) whose nodes also store
 * subtree sizes, giving O(log n) {@code rank}, {@code select} and {@code countRange}.
 *
 * <p>Null keys and values are rejected. When the comparator treats a new key as equal to a stored one,
 * the value is replaced and the original key object is kept. Not thread-safe.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class RbTreeMap<K, V> {

    /** A tree node; package-private so tests can build deliberately corrupt trees. */
    static final class Node<K, V> {
        K key;
        V val;
        Node<K, V> left;
        Node<K, V> right;
        boolean red;
        int size;
    }

    private final Comparator<? super K> cmp;
    private Node<K, V> root;

    /** Creates a map using the natural ordering of the keys. */
    @SuppressWarnings("unchecked")
    public RbTreeMap() {
        this((Comparator<? super K>) Comparator.naturalOrder());
    }

    /** Creates a map ordered by {@code comparator}. */
    public RbTreeMap(Comparator<? super K> comparator) {
        this.cmp = Objects.requireNonNull(comparator, "comparator");
    }

    /** Wraps a hand-built tree (tests only). */
    static <K, V> RbTreeMap<K, V> of(Comparator<? super K> comparator, Node<K, V> root) {
        RbTreeMap<K, V> m = new RbTreeMap<>(comparator);
        m.root = root;
        return m;
    }

    // ---- queries ----

    public int size() {
        return size(root);
    }

    public boolean isEmpty() {
        return root == null;
    }

    public V get(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> x = root;
        while (x != null) {
            int c = cmp.compare(key, x.key);
            if (c == 0) {
                return x.val;
            }
            x = c < 0 ? x.left : x.right;
        }
        return null;
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public K firstKey() {
        if (root == null) {
            throw new NoSuchElementException("empty map");
        }
        return min(root).key;
    }

    public K lastKey() {
        if (root == null) {
            throw new NoSuchElementException("empty map");
        }
        Node<K, V> x = root;
        while (x.right != null) {
            x = x.right;
        }
        return x.key;
    }

    /** Greatest key less than or equal to {@code key}. */
    public Optional<K> floorKey(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> best = null;
        for (Node<K, V> x = root; x != null; ) {
            int c = cmp.compare(key, x.key);
            if (c == 0) {
                return Optional.of(x.key);
            }
            if (c < 0) {
                x = x.left;
            } else {
                best = x;
                x = x.right;
            }
        }
        return Optional.ofNullable(best).map(n -> n.key);
    }

    /** Least key greater than or equal to {@code key}. */
    public Optional<K> ceilingKey(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> best = null;
        for (Node<K, V> x = root; x != null; ) {
            int c = cmp.compare(key, x.key);
            if (c == 0) {
                return Optional.of(x.key);
            }
            if (c > 0) {
                x = x.right;
            } else {
                best = x;
                x = x.left;
            }
        }
        return Optional.ofNullable(best).map(n -> n.key);
    }

    /** Greatest key strictly less than {@code key}. */
    public Optional<K> lowerKey(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> best = null;
        for (Node<K, V> x = root; x != null; ) {
            if (cmp.compare(key, x.key) <= 0) {
                x = x.left;
            } else {
                best = x;
                x = x.right;
            }
        }
        return Optional.ofNullable(best).map(n -> n.key);
    }

    /** Least key strictly greater than {@code key}. */
    public Optional<K> higherKey(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> best = null;
        for (Node<K, V> x = root; x != null; ) {
            if (cmp.compare(key, x.key) >= 0) {
                x = x.right;
            } else {
                best = x;
                x = x.left;
            }
        }
        return Optional.ofNullable(best).map(n -> n.key);
    }

    /** Number of stored keys strictly less than {@code key}. */
    public int rank(K key) {
        Objects.requireNonNull(key, "key");
        int r = 0;
        for (Node<K, V> x = root; x != null; ) {
            int c = cmp.compare(key, x.key);
            if (c <= 0) {
                x = x.left;
            } else {
                r += 1 + size(x.left);
                x = x.right;
            }
        }
        return r;
    }

    /** Key at 0-based sorted position {@code index}. */
    public K select(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size());
        }
        Node<K, V> x = root;
        int i = index;
        while (true) {
            int ls = size(x.left);
            if (i < ls) {
                x = x.left;
            } else if (i == ls) {
                return x.key;
            } else {
                i -= ls + 1;
                x = x.right;
            }
        }
    }

    /** Number of stored keys k with {@code lo <= k <= hi}; 0 when {@code lo > hi}. */
    public int countRange(K lo, K hi) {
        Objects.requireNonNull(lo, "lo");
        Objects.requireNonNull(hi, "hi");
        if (cmp.compare(lo, hi) > 0) {
            return 0;
        }
        return rank(hi) + (containsKey(hi) ? 1 : 0) - rank(lo);
    }

    /** Entries with {@code lo <= key <= hi}, ascending. */
    public List<Map.Entry<K, V>> entries(K lo, K hi) {
        Objects.requireNonNull(lo, "lo");
        Objects.requireNonNull(hi, "hi");
        List<Map.Entry<K, V>> out = new ArrayList<>();
        if (cmp.compare(lo, hi) <= 0) {
            collect(root, lo, hi, out);
        }
        return out;
    }

    /** All keys, ascending. */
    public List<K> keys() {
        List<K> out = new ArrayList<>(size());
        keysInto(root, out);
        return out;
    }

    // ---- updates ----

    /** Associates {@code value} with {@code key}; returns the previous value or null. */
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        V old = get(key);
        root = put(root, key, value);
        root.red = false;
        return old;
    }

    /** Removes {@code key}; returns the previous value or null. */
    public V remove(K key) {
        Objects.requireNonNull(key, "key");
        V old = get(key);
        if (old == null) {
            return null;
        }
        if (!isRed(root.left) && !isRed(root.right)) {
            root.red = true;
        }
        root = delete(root, key);
        if (root != null) {
            root.red = false;
        }
        return old;
    }

    /** Removes and returns the smallest entry. */
    public Optional<Map.Entry<K, V>> pollFirst() {
        if (root == null) {
            return Optional.empty();
        }
        Node<K, V> m = min(root);
        Map.Entry<K, V> e = Map.entry(m.key, m.val);
        remove(m.key);
        return Optional.of(e);
    }

    // ---- invariants ----

    /**
     * Verifies every structural rule.
     *
     * @throws IllegalStateException with a message starting {@code root-red}, {@code red-right},
     *     {@code double-red}, {@code black-height}, {@code order} or {@code size}
     */
    void check() {
        if (isRed(root)) {
            throw new IllegalStateException("root-red: root is red");
        }
        check(root, null, null);
    }

    /** Number of nodes on the longest root-to-leaf path. */
    int height() {
        return height(root);
    }

    /** Returns the black height of the subtree; keys must lie strictly between lo and hi. */
    private int check(Node<K, V> x, K lo, K hi) {
        if (x == null) {
            return 0;
        }
        if (lo != null && cmp.compare(x.key, lo) <= 0 || hi != null && cmp.compare(x.key, hi) >= 0) {
            throw new IllegalStateException("order: key " + x.key + " outside (" + lo + ", " + hi + ")");
        }
        if (isRed(x.right)) {
            throw new IllegalStateException("red-right: right link of " + x.key + " is red");
        }
        if (x.red && isRed(x.left)) {
            throw new IllegalStateException("double-red: " + x.key + " and its left child are red");
        }
        int lb = check(x.left, lo, x.key);
        int rb = check(x.right, x.key, hi);
        if (lb != rb) {
            throw new IllegalStateException("black-height: " + x.key + " has " + lb + " vs " + rb);
        }
        if (x.size != 1 + size(x.left) + size(x.right)) {
            throw new IllegalStateException("size: " + x.key + " stores " + x.size);
        }
        return lb + (x.red ? 0 : 1);
    }

    private int height(Node<K, V> x) {
        return x == null ? 0 : 1 + Math.max(height(x.left), height(x.right));
    }

    // ---- internals ----

    private static <K, V> int size(Node<K, V> x) {
        return x == null ? 0 : x.size;
    }

    private static <K, V> boolean isRed(Node<K, V> x) {
        return x != null && x.red;
    }

    private static <K, V> Node<K, V> min(Node<K, V> x) {
        while (x.left != null) {
            x = x.left;
        }
        return x;
    }

    private Node<K, V> put(Node<K, V> h, K key, V val) {
        if (h == null) {
            Node<K, V> n = new Node<>();
            n.key = key;
            n.val = val;
            n.red = true;
            n.size = 1;
            return n;
        }
        int c = cmp.compare(key, h.key);
        if (c < 0) {
            h.left = put(h.left, key, val);
        } else if (c > 0) {
            h.right = put(h.right, key, val);
        } else {
            h.val = val;
        }
        return balance(h);
    }

    /** Precondition: {@code key} is present. */
    private Node<K, V> delete(Node<K, V> h, K key) {
        if (cmp.compare(key, h.key) < 0) {
            if (!isRed(h.left) && !isRed(h.left.left)) {
                h = moveRedLeft(h);
            }
            h.left = delete(h.left, key);
        } else {
            if (isRed(h.left)) {
                h = rotateRight(h);
            }
            if (cmp.compare(key, h.key) == 0 && h.right == null) {
                return null;
            }
            if (!isRed(h.right) && !isRed(h.right.left)) {
                h = moveRedRight(h);
            }
            if (cmp.compare(key, h.key) == 0) {
                Node<K, V> m = min(h.right);
                h.key = m.key;
                h.val = m.val;
                h.right = deleteMin(h.right);
            } else {
                h.right = delete(h.right, key);
            }
        }
        return balance(h);
    }

    private Node<K, V> deleteMin(Node<K, V> h) {
        if (h.left == null) {
            return null;
        }
        if (!isRed(h.left) && !isRed(h.left.left)) {
            h = moveRedLeft(h);
        }
        h.left = deleteMin(h.left);
        return balance(h);
    }

    private Node<K, V> rotateLeft(Node<K, V> h) {
        Node<K, V> x = h.right;
        h.right = x.left;
        x.left = h;
        x.red = h.red;
        h.red = true;
        x.size = h.size;
        h.size = 1 + size(h.left) + size(h.right);
        return x;
    }

    private Node<K, V> rotateRight(Node<K, V> h) {
        Node<K, V> x = h.left;
        h.left = x.right;
        x.right = h;
        x.red = h.red;
        h.red = true;
        x.size = h.size;
        h.size = 1 + size(h.left) + size(h.right);
        return x;
    }

    private void flipColors(Node<K, V> h) {
        h.red = !h.red;
        h.left.red = !h.left.red;
        h.right.red = !h.right.red;
    }

    private Node<K, V> moveRedLeft(Node<K, V> h) {
        flipColors(h);
        if (isRed(h.right.left)) {
            h.right = rotateRight(h.right);
            h = rotateLeft(h);
            flipColors(h);
        }
        return h;
    }

    private Node<K, V> moveRedRight(Node<K, V> h) {
        flipColors(h);
        if (isRed(h.left.left)) {
            h = rotateRight(h);
            flipColors(h);
        }
        return h;
    }

    private Node<K, V> balance(Node<K, V> h) {
        if (isRed(h.right) && !isRed(h.left)) {
            h = rotateLeft(h);
        }
        if (isRed(h.left) && isRed(h.left.left)) {
            h = rotateRight(h);
        }
        if (isRed(h.left) && isRed(h.right)) {
            flipColors(h);
        }
        h.size = 1 + size(h.left) + size(h.right);
        return h;
    }

    private void collect(Node<K, V> x, K lo, K hi, List<Map.Entry<K, V>> out) {
        if (x == null) {
            return;
        }
        int cl = cmp.compare(lo, x.key);
        int ch = cmp.compare(hi, x.key);
        if (cl < 0) {
            collect(x.left, lo, hi, out);
        }
        if (cl <= 0 && ch >= 0) {
            out.add(Map.entry(x.key, x.val));
        }
        if (ch > 0) {
            collect(x.right, lo, hi, out);
        }
    }

    private void keysInto(Node<K, V> x, List<K> out) {
        if (x == null) {
            return;
        }
        keysInto(x.left, out);
        out.add(x.key);
        keysInto(x.right, out);
    }
}
