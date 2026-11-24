package ae.columnar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Immutable column-store table of {@code long} columns, split into segments of {@code segmentRows} rows.
 * Each column of each segment is stored with the best {@link Encoding}.
 */
public final class Table {
    private final List<String> names;
    private final Map<String, Integer> index = new HashMap<>();
    private final int segmentRows;
    private final int rowCount;
    /** segments.get(s)[c] is column c of segment s. */
    private final List<EncodedColumn[]> segments;

    private Table(List<String> names, int segmentRows, int rowCount, List<EncodedColumn[]> segments) {
        this.names = names;
        for (int i = 0; i < names.size(); i++) {
            index.put(names.get(i), i);
        }
        this.segmentRows = segmentRows;
        this.rowCount = rowCount;
        this.segments = segments;
    }

    public static Builder builder(int segmentRows, String... columns) {
        return new Builder(segmentRows, columns);
    }

    /** Collects rows; single use. */
    public static final class Builder {
        private final List<String> names;
        private final int segmentRows;
        private final List<long[]> rows = new ArrayList<>();
        private boolean built;

        private Builder(int segmentRows, String[] columns) {
            if (segmentRows < 1) {
                throw new IllegalArgumentException("segmentRows must be positive");
            }
            if (columns.length == 0) {
                throw new IllegalArgumentException("at least one column required");
            }
            if (Arrays.stream(columns).distinct().count() != columns.length) {
                throw new IllegalArgumentException("duplicate column name");
            }
            this.segmentRows = segmentRows;
            this.names = List.of(columns);
        }

        public Builder append(long... row) {
            if (built) {
                throw new IllegalStateException("already built");
            }
            if (row.length != names.size()) {
                throw new IllegalArgumentException("expected " + names.size() + " values, got " + row.length);
            }
            rows.add(row.clone());
            return this;
        }

        public Table build() {
            if (built) {
                throw new IllegalStateException("already built");
            }
            built = true;
            List<EncodedColumn[]> segs = new ArrayList<>();
            for (int from = 0; from < rows.size(); from += segmentRows) {
                int to = Math.min(rows.size(), from + segmentRows);
                EncodedColumn[] cols = new EncodedColumn[names.size()];
                for (int c = 0; c < cols.length; c++) {
                    long[] v = new long[to - from];
                    for (int i = from; i < to; i++) {
                        v[i - from] = rows.get(i)[c];
                    }
                    cols[c] = EncodedColumn.encodeBest(v);
                }
                segs.add(cols);
            }
            return new Table(names, segmentRows, rows.size(), segs);
        }
    }

    public int rowCount() {
        return rowCount;
    }

    public int segmentCount() {
        return segments.size();
    }

    private int column(String name) {
        Integer c = name == null ? null : index.get(name);
        if (c == null) {
            throw new IllegalArgumentException("unknown column " + name);
        }
        return c;
    }

    /** Value of {@code column} at global {@code row}. */
    public long get(String column, int row) {
        int c = column(column);
        if (row < 0 || row >= rowCount) {
            throw new IndexOutOfBoundsException("row " + row + " of " + rowCount);
        }
        return segments.get(row / segmentRows)[c].get(row % segmentRows);
    }

    /** The encoding chosen for {@code column} in segment {@code segment}. */
    public Encoding encodingOf(String column, int segment) {
        int c = column(column);
        if (segment < 0 || segment >= segments.size()) {
            throw new IndexOutOfBoundsException("segment " + segment);
        }
        return segments.get(segment)[c].encoding();
    }

    /** Rows satisfying every predicate (AND), ascending, with per-segment pruning statistics. */
    public Selection select(List<Pred> preds) {
        int[] cols = new int[preds.size()];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = column(preds.get(i).column());
        }
        int[] out = new int[rowCount];
        int count = 0;
        int pruned = 0;
        int all = 0;
        int scanned = 0;
        for (int s = 0; s < segments.size(); s++) {
            EncodedColumn[] seg = segments.get(s);
            int rows = seg[0].size();
            boolean none = false;
            boolean[] needsScan = new boolean[cols.length];
            boolean anyScan = false;
            for (int i = 0; i < cols.length && !none; i++) {
                EncodedColumn c = seg[cols[i]];
                Pred p = preds.get(i);
                switch (p.op().prune(c.min(), c.max(), p.value())) {
                    case NONE -> none = true;
                    case MAYBE -> {
                        needsScan[i] = true;
                        anyScan = true;
                    }
                    case ALL -> { }
                }
            }
            if (none) {
                pruned++;
                continue;
            }
            int base = s * segmentRows;
            if (!anyScan) {
                all++;
                for (int r = 0; r < rows; r++) {
                    out[count++] = base + r;
                }
                continue;
            }
            scanned++;
            BitSet hits = null;
            for (int i = 0; i < cols.length; i++) {
                if (needsScan[i]) {
                    BitSet b = seg[cols[i]].scan(preds.get(i).op(), preds.get(i).value());
                    if (hits == null) {
                        hits = b;
                    } else {
                        hits.and(b);
                    }
                }
            }
            for (int r = hits.nextSetBit(0); r >= 0; r = hits.nextSetBit(r + 1)) {
                out[count++] = base + r;
            }
        }
        return new Selection(Arrays.copyOf(out, count), pruned, all, scanned);
    }

    /**
     * Aggregate over the selected rows. COUNT ignores {@code aggColumn} (may be null). SUM of no rows is 0;
     * MIN/MAX of no rows is empty; SUM overflow throws {@link ArithmeticException}.
     */
    public OptionalLong aggregate(List<Pred> preds, Agg agg, String aggColumn) {
        Objects.requireNonNull(agg, "agg");
        int c = agg == Agg.COUNT ? -1 : column(aggColumn);
        int[] rows = select(preds).rows();
        if (agg == Agg.COUNT) {
            return OptionalLong.of(rows.length);
        }
        if (rows.length == 0) {
            return agg == Agg.SUM ? OptionalLong.of(0) : OptionalLong.empty();
        }
        long acc = initial(agg);
        for (int r : rows) {
            acc = combine(agg, acc, valueAt(c, r));
        }
        return OptionalLong.of(acc);
    }

    /** Aggregate per distinct value of {@code groupColumn}; groups with no selected rows are absent. */
    public SortedMap<Long, Long> groupBy(List<Pred> preds, String groupColumn, Agg agg, String aggColumn) {
        Objects.requireNonNull(agg, "agg");
        int g = column(groupColumn);
        int c = agg == Agg.COUNT ? -1 : column(aggColumn);
        TreeMap<Long, Long> out = new TreeMap<>();
        for (int r : select(preds).rows()) {
            long key = valueAt(g, r);
            long v = agg == Agg.COUNT ? 1 : valueAt(c, r);
            Long cur = out.get(key);
            out.put(key, cur == null ? (agg == Agg.COUNT ? 1 : v) : combine(agg, cur, v));
        }
        return out;
    }

    private long valueAt(int column, int row) {
        return segments.get(row / segmentRows)[column].get(row % segmentRows);
    }

    private static long initial(Agg agg) {
        return switch (agg) {
            case SUM, COUNT -> 0;
            case MIN -> Long.MAX_VALUE;
            case MAX -> Long.MIN_VALUE;
        };
    }

    private static long combine(Agg agg, long acc, long v) {
        return switch (agg) {
            case COUNT -> Math.addExact(acc, 1);
            case SUM -> Math.addExact(acc, v);
            case MIN -> Math.min(acc, v);
            case MAX -> Math.max(acc, v);
        };
    }
}
