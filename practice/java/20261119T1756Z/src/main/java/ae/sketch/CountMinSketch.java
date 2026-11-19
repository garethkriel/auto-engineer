package ae.sketch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Count-Min sketch: estimates never fall below the true count. Not thread safe. */
public final class CountMinSketch {
    static final int MAGIC = 0x434d534b; // "CMSK"

    private final int width;
    private final int depth;
    private final boolean conservative;
    private final long[][] table;
    private long total;

    private CountMinSketch(int width, int depth, boolean conservative) {
        this.width = width;
        this.depth = depth;
        this.conservative = conservative;
        this.table = new long[depth][width];
    }

    /** Error at most {@code eps * total} with probability at least {@code 1 - delta}. */
    public static CountMinSketch forErrorBounds(double eps, double delta, boolean conservative) {
        if (!(eps > 0.0 && eps < 1.0) || !(delta > 0.0 && delta < 1.0)) {
            throw new IllegalArgumentException("eps and delta must be in (0,1)");
        }
        double w = Math.ceil(Math.E / eps);
        if (w > 1 << 24) {
            throw new IllegalArgumentException("sketch too wide");
        }
        return withShape((int) w, (int) Math.ceil(Math.log(1.0 / delta)), conservative);
    }

    public static CountMinSketch withShape(int width, int depth, boolean conservative) {
        if (width < 1 || depth < 1) {
            throw new IllegalArgumentException("width and depth must be positive");
        }
        return new CountMinSketch(width, depth, conservative);
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    /** Sum of all added counts. */
    public long total() {
        return total;
    }

    public void add(String key, long count) {
        add(Objects.requireNonNull(key, "key").getBytes(StandardCharsets.UTF_8), count);
    }

    public void add(byte[] key, long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        long newTotal = Math.addExact(total, count);
        int[] cols = columns(key);
        if (conservative) {
            long target = Math.addExact(minAt(cols), count);
            for (int r = 0; r < depth; r++) {
                if (table[r][cols[r]] < target) {
                    table[r][cols[r]] = target;
                }
            }
        } else {
            // compute every sum first so an overflow leaves the table untouched
            long[] sums = new long[depth];
            for (int r = 0; r < depth; r++) {
                sums[r] = Math.addExact(table[r][cols[r]], count);
            }
            for (int r = 0; r < depth; r++) {
                table[r][cols[r]] = sums[r];
            }
        }
        total = newTotal;
    }

    public long estimate(String key) {
        return estimate(Objects.requireNonNull(key, "key").getBytes(StandardCharsets.UTF_8));
    }

    public long estimate(byte[] key) {
        return minAt(columns(key));
    }

    private long minAt(int[] cols) {
        long min = Long.MAX_VALUE;
        for (int r = 0; r < depth; r++) {
            min = Math.min(min, table[r][cols[r]]);
        }
        return min;
    }

    private int[] columns(byte[] key) {
        int[] cols = new int[depth];
        for (int r = 0; r < depth; r++) {
            cols[r] = (int) Long.remainderUnsigned(Hash64.hash(key, r), width);
        }
        return cols;
    }

    /** Adds {@code other}'s counters into this sketch; shapes (and update mode) must match. */
    public void merge(CountMinSketch other) {
        Objects.requireNonNull(other, "other");
        if (other.width != width || other.depth != depth) {
            throw new IllegalArgumentException("shape mismatch");
        }
        long newTotal = Math.addExact(total, other.total);
        long[][] sums = new long[depth][width];
        for (int r = 0; r < depth; r++) {
            for (int c = 0; c < width; c++) {
                sums[r][c] = Math.addExact(table[r][c], other.table[r][c]);
            }
        }
        for (int r = 0; r < depth; r++) {
            table[r] = sums[r];
        }
        total = newTotal;
    }

    public byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 + 1 + 8 + depth * width * 8);
        b.putInt(MAGIC).putInt(width).putInt(depth).put((byte) (conservative ? 1 : 0)).putLong(total);
        for (long[] row : table) {
            for (long v : row) {
                b.putLong(v);
            }
        }
        return Codec.seal(b.array());
    }

    public static CountMinSketch fromBytes(byte[] image) {
        ByteBuffer b = Codec.open(image, MAGIC, 17);
        int w = b.getInt();
        int d = b.getInt();
        int flag = b.get();
        long total = b.getLong();
        if (w < 1 || d < 1 || (flag != 0 && flag != 1) || total < 0) {
            throw new IllegalArgumentException("bad header");
        }
        if ((long) w * d * 8 != b.remaining()) {
            throw new IllegalArgumentException("length does not match shape");
        }
        CountMinSketch s = new CountMinSketch(w, d, flag == 1);
        for (int r = 0; r < d; r++) {
            for (int c = 0; c < w; c++) {
                long v = b.getLong();
                if (v < 0) {
                    throw new IllegalArgumentException("negative counter");
                }
                s.table[r][c] = v;
            }
        }
        s.total = total;
        return s;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CountMinSketch s && s.width == width && s.depth == depth && s.total == total
            && s.conservative == conservative && Arrays.deepEquals(s.table, table);
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, depth, total, conservative) * 31 + Arrays.deepHashCode(table);
    }
}
