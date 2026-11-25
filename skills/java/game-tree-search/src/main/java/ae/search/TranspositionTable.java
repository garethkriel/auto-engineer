package ae.search;

/**
 * Fixed-size transposition table. A slot holds one entry whose full key is compared on probe, so two
 * positions that share a slot never see each other's data.
 *
 * <p>Replacement: an empty slot or a different key is always overwritten; the same key is overwritten
 * only by an entry of at least the stored depth.
 */
public final class TranspositionTable {
    /** What the stored value says about the true value of the position. */
    public enum Bound { EXACT, LOWER, UPPER }

    /** One stored search result. */
    public record Entry(long key, int depth, Bound bound, int value, int move) { }

    private final Entry[] slots;

    public TranspositionTable(int entries) {
        if (entries <= 0) {
            throw new IllegalArgumentException("entries must be positive: " + entries);
        }
        slots = new Entry[entries];
    }

    private int slot(long key) {
        return (int) Math.floorMod(key, (long) slots.length);
    }

    /** The entry stored for exactly this key, or null. */
    public Entry probe(long key) {
        Entry e = slots[slot(key)];
        return e != null && e.key() == key ? e : null;
    }

    public void store(long key, int depth, Bound bound, int value, int move) {
        int i = slot(key);
        Entry old = slots[i];
        if (old != null && old.key() == key && old.depth() > depth) {
            return;
        }
        slots[i] = new Entry(key, depth, bound, value, move);
    }

    public void clear() {
        java.util.Arrays.fill(slots, null);
    }
}
