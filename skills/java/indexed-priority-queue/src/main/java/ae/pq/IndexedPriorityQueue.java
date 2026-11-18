package ae.pq;

import java.util.Arrays;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * A min-priority queue over integer ids {@code 0..capacity-1}, each holding at most one key.
 *
 * <p>Backed by a d-ary heap plus a position index, so {@link #changeKey}, {@link #remove} and
 * {@link #contains} take O(log n) / O(log n) / O(1). Equal keys are ordered by smaller id, which
 * makes polling fully deterministic. Not thread-safe. If the comparator throws in the middle of a
 * sift the heap may be left inconsistent; argument validation always happens before mutation.
 *
 * @param <K> key type
 */
public final class IndexedPriorityQueue<K> {
    private static final int ABSENT = -1;

    private final Comparator<? super K> order;
    private final int arity;
    private final int[] heap;   // heap position -> id
    private final int[] pos;    // id -> heap position, or ABSENT
    private final Object[] keys; // id -> key
    private int size;

    /**
     * @param capacity number of valid ids ({@code >= 0})
     * @param order    key ordering; smaller keys are polled first
     * @param arity    children per node, 2..16
     */
    public IndexedPriorityQueue(int capacity, Comparator<? super K> order, int arity) {
        Objects.requireNonNull(order, "order");
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: " + capacity);
        }
        if (arity < 2 || arity > 16) {
            throw new IllegalArgumentException("arity must be in 2..16: " + arity);
        }
        this.order = order;
        this.arity = arity;
        this.heap = new int[capacity];
        this.pos = new int[capacity];
        this.keys = new Object[capacity];
        Arrays.fill(pos, ABSENT);
    }

    public void insert(int id, K key) {
        checkRange(id);
        Objects.requireNonNull(key, "key");
        if (pos[id] != ABSENT) {
            throw new IllegalStateException("id already present: " + id);
        }
        keys[id] = key;
        heap[size] = id;
        pos[id] = size;
        size++;
        siftUp(size - 1);
    }

    public boolean contains(int id) {
        return id >= 0 && id < pos.length && pos[id] != ABSENT;
    }

    public K keyOf(int id) {
        requirePresent(id);
        return key(id);
    }

    public void changeKey(int id, K key) {
        requirePresent(id);
        Objects.requireNonNull(key, "key");
        keys[id] = key;
        int p = pos[id];
        siftUp(p);
        siftDown(pos[id]);
    }

    public K remove(int id) {
        requirePresent(id);
        K removed = key(id);
        int p = pos[id];
        size--;
        if (p != size) {
            int moved = heap[size];
            heap[p] = moved;
            pos[moved] = p;
            siftUp(p);
            siftDown(pos[moved]);
        }
        pos[id] = ABSENT;
        keys[id] = null;
        return removed;
    }

    public int peekId() {
        requireNonEmpty();
        return heap[0];
    }

    public K peekKey() {
        requireNonEmpty();
        return key(heap[0]);
    }

    public int pollId() {
        requireNonEmpty();
        int id = heap[0];
        remove(id);
        return id;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        for (int i = 0; i < size; i++) {
            int id = heap[i];
            pos[id] = ABSENT;
            keys[id] = null;
        }
        size = 0;
    }

    // ---- internals ----

    @SuppressWarnings("unchecked")
    private K key(int id) {
        return (K) keys[id];
    }

    private void checkRange(int id) {
        if (id < 0 || id >= pos.length) {
            throw new IndexOutOfBoundsException("id " + id + " not in [0," + pos.length + ")");
        }
    }

    private void requirePresent(int id) {
        if (!contains(id)) {
            throw new NoSuchElementException("id not present: " + id);
        }
    }

    private void requireNonEmpty() {
        if (size == 0) {
            throw new NoSuchElementException("queue is empty");
        }
    }

    /** True when the entry with id {@code a} must come before the one with id {@code b}. */
    private boolean before(int a, int b) {
        int c = order.compare(key(a), key(b));
        return c < 0 || (c == 0 && a < b);
    }

    private void siftUp(int i) {
        int id = heap[i];
        while (i > 0) {
            int parent = (i - 1) / arity;
            int pid = heap[parent];
            if (!before(id, pid)) {
                break;
            }
            heap[i] = pid;
            pos[pid] = i;
            i = parent;
        }
        heap[i] = id;
        pos[id] = i;
    }

    private void siftDown(int i) {
        int id = heap[i];
        while (true) {
            long firstChild = (long) i * arity + 1;
            if (firstChild >= size) {
                break;
            }
            int first = (int) firstChild;
            int last = Math.min(first + arity, size);
            int best = first;
            for (int c = first + 1; c < last; c++) {
                if (before(heap[c], heap[best])) {
                    best = c;
                }
            }
            if (!before(heap[best], id)) {
                break;
            }
            heap[i] = heap[best];
            pos[heap[i]] = i;
            i = best;
        }
        heap[i] = id;
        pos[id] = i;
    }
}
