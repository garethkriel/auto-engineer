package perf;

import java.util.Arrays;
import java.util.OptionalInt;

/** Open-addressing int-to-int hash map: linear probing, backward-shift deletion, load factor 0.5. */
public final class IntIntMap {
    /** Callback for {@link #forEach}. */
    @FunctionalInterface
    public interface IntIntConsumer {
        void accept(int key, int value);
    }

    private static final int MIN_CAPACITY = 8;

    private int[] keys;
    private int[] values;
    private boolean[] used;
    private int mask;
    private int size;

    public IntIntMap() {
        allocate(MIN_CAPACITY);
    }

    private void allocate(int capacity) {
        keys = new int[capacity];
        values = new int[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
    }

    private static int hash(int key) {
        int h = key * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /** Index holding {@code key}, or the empty slot where it would go (negated minus one). */
    private int find(int key) {
        int i = hash(key) & mask;
        while (used[i]) {
            if (keys[i] == key) {
                return i;
            }
            i = (i + 1) & mask;
        }
        return -i - 1;
    }

    public int size() {
        return size;
    }

    public boolean containsKey(int key) {
        return find(key) >= 0;
    }

    public OptionalInt get(int key) {
        int i = find(key);
        return i >= 0 ? OptionalInt.of(values[i]) : OptionalInt.empty();
    }

    public int getOrDefault(int key, int def) {
        int i = find(key);
        return i >= 0 ? values[i] : def;
    }

    public OptionalInt put(int key, int value) {
        int i = find(key);
        if (i >= 0) {
            int old = values[i];
            values[i] = value;
            return OptionalInt.of(old);
        }
        insertAt(-i - 1, key, value);
        return OptionalInt.empty();
    }

    /** Adds {@code delta} to the value (absent counts as 0) and returns the new value. */
    public int addTo(int key, int delta) {
        int i = find(key);
        if (i >= 0) {
            int updated = Math.addExact(values[i], delta);
            values[i] = updated;
            return updated;
        }
        insertAt(-i - 1, key, delta);
        return delta;
    }

    private void insertAt(int index, int key, int value) {
        int at = index;
        if ((size + 1) * 2 > keys.length) {
            grow();
            at = -find(key) - 1;
        }
        used[at] = true;
        keys[at] = key;
        values[at] = value;
        size++;
    }

    private void grow() {
        int[] oldKeys = keys;
        int[] oldValues = values;
        boolean[] oldUsed = used;
        allocate(oldKeys.length * 2);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                int at = -find(oldKeys[i]) - 1;
                used[at] = true;
                keys[at] = oldKeys[i];
                values[at] = oldValues[i];
            }
        }
    }

    public OptionalInt remove(int key) {
        int i = find(key);
        if (i < 0) {
            return OptionalInt.empty();
        }
        int old = values[i];
        // Backward-shift: pull later cluster members into the hole when their home allows it.
        int hole = i;
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            if (!used[j]) {
                break;
            }
            int home = hash(keys[j]) & mask;
            // j may move into hole iff home is NOT cyclically within (hole, j].
            boolean homeInRange = hole <= j ? (hole < home && home <= j) : (hole < home || home <= j);
            if (!homeInRange) {
                keys[hole] = keys[j];
                values[hole] = values[j];
                hole = j;
            }
        }
        used[hole] = false;
        size--;
        return OptionalInt.of(old);
    }

    public void clear() {
        Arrays.fill(used, false);
        size = 0;
    }

    public void forEach(IntIntConsumer consumer) {
        for (int i = 0; i < keys.length; i++) {
            if (used[i]) {
                consumer.accept(keys[i], values[i]);
            }
        }
    }

    public int[] sortedKeys() {
        int[] out = new int[size];
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (used[i]) {
                out[n++] = keys[i];
            }
        }
        Arrays.sort(out);
        return out;
    }

    /** Current table capacity (a power of two); exposed for tests. */
    int capacity() {
        return keys.length;
    }
}
