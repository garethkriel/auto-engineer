package ae.roaring;

/**
 * Bit-sliced index over a column of non-negative ints (row id = array position). Slice {@code b} holds the rows whose
 * value has bit {@code b} set; comparisons walk the slices from the most significant bit down (O'Neil &amp; Quass).
 */
public final class BitSlicedIndex {
    private static final int BITS = 31;

    private final RoaringBitmap[] slices = new RoaringBitmap[BITS];
    private final RoaringBitmap all = new RoaringBitmap();
    private final int rows;

    private BitSlicedIndex(int[] values) {
        rows = values.length;
        for (int b = 0; b < BITS; b++) slices[b] = new RoaringBitmap();
        for (int row = 0; row < values.length; row++) {
            if (values[row] < 0) throw new IllegalArgumentException("negative value " + values[row] + " at row " + row);
            for (int b = 0; b < BITS; b++) {
                if ((values[row] >>> b & 1) != 0) slices[b].add(row);
            }
        }
        all.addRange(0, rows);
        for (RoaringBitmap s : slices) s.runOptimize();
    }

    public static BitSlicedIndex of(int[] values) {
        return new BitSlicedIndex(values);
    }

    public int count() {
        return rows;
    }

    /** Rows with value &lt; v (lt) and rows with value == v (eq), for v in [0, MAX_VALUE]. */
    private RoaringBitmap[] ltEq(int v) {
        RoaringBitmap eq = all.copy(), lt = new RoaringBitmap();
        for (int b = BITS - 1; b >= 0; b--) {
            if ((v >>> b & 1) != 0) {
                lt = RoaringBitmap.or(lt, RoaringBitmap.andNot(eq, slices[b]));
                eq = RoaringBitmap.and(eq, slices[b]);
            } else {
                eq = RoaringBitmap.andNot(eq, slices[b]);
            }
        }
        return new RoaringBitmap[] {lt, eq};
    }

    public RoaringBitmap equalTo(int v) {
        return v < 0 ? new RoaringBitmap() : ltEq(v)[1];
    }

    public RoaringBitmap lessThan(int v) {
        return v <= 0 ? new RoaringBitmap() : ltEq(v)[0];
    }

    public RoaringBitmap lessOrEqual(int v) {
        if (v < 0) return new RoaringBitmap();
        RoaringBitmap[] r = ltEq(v);
        return RoaringBitmap.or(r[0], r[1]);
    }

    public RoaringBitmap greaterThan(int v) {
        return RoaringBitmap.andNot(all, lessOrEqual(v));
    }

    public RoaringBitmap greaterOrEqual(int v) {
        return RoaringBitmap.andNot(all, lessThan(v));
    }

    public RoaringBitmap between(int lo, int hi) {
        if (lo > hi) return new RoaringBitmap();
        return RoaringBitmap.and(greaterOrEqual(lo), lessOrEqual(hi));
    }

    /** Sum of the column over the rows in {@code filter}, or over every row when {@code filter} is null. */
    public long sum(RoaringBitmap filter) {
        long total = 0;
        for (int b = 0; b < BITS; b++) {
            RoaringBitmap hit = filter == null ? slices[b] : RoaringBitmap.and(slices[b], filter);
            total += hit.cardinality() << b;
        }
        return total;
    }
}
