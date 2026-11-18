package ae.skip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Sorted map backed by an <em>indexable</em> skip list. Every forward link stores a span, the number
 * of level-0 steps it skips, so {@link #rank}, {@link #select} and {@link #countRange} take expected
 * O(log n) time. Levels come from a seeded {@link SplittableRandom}, so shapes are reproducible.
 * Not thread-safe. Null keys and null values are rejected.
 *
 * <p>Positions: the header is position 0, the i-th smallest entry is position i + 1, and the end of
 * the list (a null link) is position size + 1. A link's span is target position minus source position.
 */
public final class SkipListMap<K, V> implements Iterable<SkipListMap.Entry<K, V>> {

    /** Immutable key/value snapshot. */
    public record Entry<K, V>(K key, V value) {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] next;
        final int[] span;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int levels) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node[levels];
            this.span = new int[levels];
        }

        Entry<K, V> entry() {
            return new Entry<>(key, value);
        }
    }

    private final Comparator<? super K> cmp;
    private final SplittableRandom rng;
    private final Node<K, V> head = new Node<>(null, null, MAX_LEVEL);
    private int height = 1;
    private int size;
    private int modCount;

    public SkipListMap() {
        this(null, 1L);
    }

    /** @param cmp ordering, or {@code null} for natural ordering */
    @SuppressWarnings("unchecked")
    public SkipListMap(Comparator<? super K> cmp, long seed) {
        this.cmp = cmp != null ? cmp : (Comparator<? super K>) Comparator.naturalOrder();
        this.rng = new SplittableRandom(seed);
        resetHead();
    }

    private void resetHead() {
        for (int i = 0; i < MAX_LEVEL; i++) {
            head.next[i] = null;
            head.span[i] = size + 1;
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
        height = 1;
        modCount++;
        resetHead();
    }

    private int randomLevel() {
        int level = 1 + Long.numberOfTrailingZeros(rng.nextLong() | (1L << (MAX_LEVEL - 1)));
        return Math.min(level, MAX_LEVEL);
    }

    private int compare(K a, K b) {
        return cmp.compare(a, b);
    }

    /** Last node strictly less than key (head if none); also reports its position via {@code pos[0]}. */
    private Node<K, V> predecessor(K key, boolean inclusive, int[] pos) {
        Node<K, V> x = head;
        int p = 0;
        for (int i = height - 1; i >= 0; i--) {
            while (x.next[i] != null && advance(x.next[i].key, key, inclusive)) {
                p += x.span[i];
                x = x.next[i];
            }
        }
        if (pos != null) {
            pos[0] = p;
        }
        return x;
    }

    private boolean advance(K candidate, K key, boolean inclusive) {
        int c = compare(candidate, key);
        return inclusive ? c <= 0 : c < 0;
    }

    public V get(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> n = predecessor(key, false, null).next[0];
        return n != null && compare(n.key, key) == 0 ? n.value : null;
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public V put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        @SuppressWarnings("unchecked")
        Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
        int[] rank = new int[MAX_LEVEL];
        Node<K, V> x = head;
        int p = 0;
        for (int i = height - 1; i >= 0; i--) {
            while (x.next[i] != null && compare(x.next[i].key, key) < 0) {
                p += x.span[i];
                x = x.next[i];
            }
            update[i] = x;
            rank[i] = p;
        }
        Node<K, V> existing = x.next[0];
        if (existing != null && compare(existing.key, key) == 0) {
            V old = existing.value;
            existing.value = value;
            return old;
        }
        int level = randomLevel();
        for (int i = height; i < level; i++) {
            update[i] = head;
            rank[i] = 0;
            // head.span[i] already equals size + 1: the end position seen from the header.
        }
        Node<K, V> node = new Node<>(key, value, level);
        int below = rank[0]; // position of the predecessor on level 0
        for (int i = 0; i < level; i++) {
            node.next[i] = update[i].next[i];
            update[i].next[i] = node;
            node.span[i] = update[i].span[i] - (below - rank[i]);
            update[i].span[i] = below - rank[i] + 1;
        }
        for (int i = level; i < Math.max(height, level); i++) {
            update[i].span[i]++;
        }
        height = Math.max(height, level);
        // Unused header levels above height must also see the longer list.
        for (int i = height; i < MAX_LEVEL; i++) {
            head.span[i]++;
        }
        size++;
        modCount++;
        return null;
    }

    public V remove(K key) {
        Objects.requireNonNull(key, "key");
        @SuppressWarnings("unchecked")
        Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
        Node<K, V> x = head;
        for (int i = height - 1; i >= 0; i--) {
            while (x.next[i] != null && compare(x.next[i].key, key) < 0) {
                x = x.next[i];
            }
            update[i] = x;
        }
        Node<K, V> target = x.next[0];
        if (target == null || compare(target.key, key) != 0) {
            return null;
        }
        for (int i = 0; i < height; i++) {
            if (update[i].next[i] == target) {
                update[i].span[i] += target.span[i] - 1;
                update[i].next[i] = target.next[i];
            } else {
                update[i].span[i]--;
            }
        }
        for (int i = height; i < MAX_LEVEL; i++) {
            head.span[i]--;
        }
        while (height > 1 && head.next[height - 1] == null) {
            height--;
        }
        size--;
        modCount++;
        return target.value;
    }

    public Optional<Entry<K, V>> first() {
        return Optional.ofNullable(head.next[0]).map(Node::entry);
    }

    public Optional<Entry<K, V>> last() {
        Node<K, V> x = head;
        for (int i = height - 1; i >= 0; i--) {
            while (x.next[i] != null) {
                x = x.next[i];
            }
        }
        return x == head ? Optional.empty() : Optional.of(x.entry());
    }

    /** Greatest entry with key {@code <= k}. */
    public Optional<Entry<K, V>> floor(K k) {
        Objects.requireNonNull(k, "key");
        Node<K, V> n = predecessor(k, true, null);
        return n == head ? Optional.empty() : Optional.of(n.entry());
    }

    /** Least entry with key {@code >= k}. */
    public Optional<Entry<K, V>> ceiling(K k) {
        Objects.requireNonNull(k, "key");
        return Optional.ofNullable(predecessor(k, false, null).next[0]).map(Node::entry);
    }

    /** Greatest entry with key {@code < k}. */
    public Optional<Entry<K, V>> lower(K k) {
        Objects.requireNonNull(k, "key");
        Node<K, V> n = predecessor(k, false, null);
        return n == head ? Optional.empty() : Optional.of(n.entry());
    }

    /** Least entry with key {@code > k}. */
    public Optional<Entry<K, V>> higher(K k) {
        Objects.requireNonNull(k, "key");
        return Optional.ofNullable(predecessor(k, true, null).next[0]).map(Node::entry);
    }

    /** Number of keys strictly less than {@code key}; the key need not be present. */
    public int rank(K key) {
        Objects.requireNonNull(key, "key");
        int[] pos = new int[1];
        predecessor(key, false, pos);
        return pos[0]; // position of the last smaller node == how many smaller nodes exist
    }

    /** The {@code index}-th smallest entry, 0-based. */
    public Entry<K, V> select(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
        int target = index + 1;
        Node<K, V> x = head;
        int p = 0;
        for (int i = height - 1; i >= 0; i--) {
            while (x.next[i] != null && p + x.span[i] <= target) {
                p += x.span[i];
                x = x.next[i];
            }
            if (p == target) {
                break;
            }
        }
        return x.entry();
    }

    /** Entries with {@code from <= key < to}, ascending; empty when {@code from >= to}. */
    public List<Entry<K, V>> range(K fromInclusive, K toExclusive) {
        Objects.requireNonNull(fromInclusive, "from");
        Objects.requireNonNull(toExclusive, "to");
        if (compare(fromInclusive, toExclusive) >= 0) {
            return List.of();
        }
        List<Entry<K, V>> out = new ArrayList<>();
        for (Node<K, V> n = predecessor(fromInclusive, false, null).next[0];
                n != null && compare(n.key, toExclusive) < 0;
                n = n.next[0]) {
            out.add(n.entry());
        }
        return Collections.unmodifiableList(out);
    }

    /** Number of keys in {@code [from, to)} in expected O(log n). */
    public int countRange(K fromInclusive, K toExclusive) {
        Objects.requireNonNull(fromInclusive, "from");
        Objects.requireNonNull(toExclusive, "to");
        if (compare(fromInclusive, toExclusive) >= 0) {
            return 0;
        }
        return rank(toExclusive) - rank(fromInclusive);
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
        return new Iterator<>() {
            private Node<K, V> cursor = head.next[0];
            private final int expected = modCount;

            @Override
            public boolean hasNext() {
                return cursor != null;
            }

            @Override
            public Entry<K, V> next() {
                if (expected != modCount) {
                    throw new ConcurrentModificationException();
                }
                if (cursor == null) {
                    throw new NoSuchElementException();
                }
                Entry<K, V> e = cursor.entry();
                cursor = cursor.next[0];
                return e;
            }
        };
    }

    // ---- test hooks (package-private) ----

    int height() {
        return height;
    }

    /** Number of nodes present on each level, level 0 first. */
    List<Integer> levelCounts() {
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            int c = 0;
            for (Node<K, V> n = head.next[i]; n != null; n = n.next[i]) {
                c++;
            }
            counts.add(c);
        }
        return counts;
    }

    /** Throws {@link AssertionError} if ordering, spans, size or height are inconsistent. */
    void checkInvariants() {
        // level-0 positions
        java.util.IdentityHashMap<Node<K, V>, Integer> pos = new java.util.IdentityHashMap<>();
        pos.put(head, 0);
        int count = 0;
        Node<K, V> prev = null;
        for (Node<K, V> n = head.next[0]; n != null; n = n.next[0]) {
            count++;
            pos.put(n, count);
            if (prev != null && compare(prev.key, n.key) >= 0) {
                throw new AssertionError("level 0 not strictly ascending at " + n.key);
            }
            prev = n;
        }
        if (count != size) {
            throw new AssertionError("size " + size + " but " + count + " nodes");
        }
        for (int i = 0; i < MAX_LEVEL; i++) {
            if (i >= height && head.next[i] != null) {
                throw new AssertionError("level " + i + " above height is linked");
            }
            Node<K, V> x = head;
            while (true) {
                int from = pos.get(x);
                int to = x.next[i] == null ? size + 1 : pos.get(x.next[i]);
                if (x.span[i] != to - from) {
                    throw new AssertionError("level " + i + " span " + x.span[i] + " expected " + (to - from)
                            + " at position " + from);
                }
                if (x.next[i] == null) {
                    break;
                }
                x = x.next[i];
                if (x.next.length <= i) {
                    throw new AssertionError("node linked above its own level");
                }
            }
        }
        if (height > 1 && head.next[height - 1] == null) {
            throw new AssertionError("height " + height + " has an empty top level");
        }
    }
}
