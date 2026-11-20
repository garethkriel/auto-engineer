package ae.roaring;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.IntConsumer;
import java.util.zip.CRC32;

/**
 * A compressed set of unsigned 32-bit integers. An {@code int} argument is interpreted as unsigned and iteration is in
 * unsigned order. Not thread-safe.
 */
public final class RoaringBitmap {
    /** Serialisation magic ("ROAR"). */
    public static final int MAGIC = 0x524F4152;
    static final int VERSION = 1;
    private static final long UNIVERSE = 1L << 32;
    private static final int HEADER_SIZE = 4 + 1 + 4;
    private static final int CRC_SIZE = 4;

    private int[] keys = new int[4];
    private Container[] cs = new Container[4];
    private int n;

    public RoaringBitmap() {}

    // ---------------- point operations ----------------

    private int find(int key) {
        return Arrays.binarySearch(keys, 0, n, key);
    }

    private void insertAt(int idx, int key, Container c) {
        if (n == keys.length) {
            keys = Arrays.copyOf(keys, n * 2);
            cs = Arrays.copyOf(cs, n * 2);
        }
        System.arraycopy(keys, idx, keys, idx + 1, n - idx);
        System.arraycopy(cs, idx, cs, idx + 1, n - idx);
        keys[idx] = key;
        cs[idx] = c;
        n++;
    }

    private void removeAt(int idx) {
        System.arraycopy(keys, idx + 1, keys, idx, n - idx - 1);
        System.arraycopy(cs, idx + 1, cs, idx, n - idx - 1);
        n--;
        cs[n] = null;
    }

    /** Stores {@code c} at {@code key}: idx is the result of {@link #find} (negative when absent). */
    private void store(int key, int idx, Container c) {
        if (idx >= 0) {
            if (c == null) removeAt(idx);
            else cs[idx] = c;
        } else if (c != null) {
            insertAt(-idx - 1, key, c);
        }
    }

    /** @return true when the value was not present. */
    public boolean add(int x) {
        int key = x >>> 16, low = x & 0xFFFF;
        int idx = find(key);
        if (idx < 0) {
            insertAt(-idx - 1, key, new Container.Array(new char[] {(char) low}));
            return true;
        }
        if (cs[idx].contains(low)) return false;
        cs[idx] = cs[idx].add(low);
        return true;
    }

    /** @return true when the value was present. */
    public boolean remove(int x) {
        int key = x >>> 16, low = x & 0xFFFF;
        int idx = find(key);
        if (idx < 0 || !cs[idx].contains(low)) return false;
        store(key, idx, cs[idx].remove(low));
        return true;
    }

    public boolean contains(int x) {
        int idx = find(x >>> 16);
        return idx >= 0 && cs[idx].contains(x & 0xFFFF);
    }

    public long cardinality() {
        long c = 0;
        for (int i = 0; i < n; i++) c += cs[i].cardinality();
        return c;
    }

    public boolean isEmpty() {
        return n == 0;
    }

    // ---------------- ranges ----------------

    private static void checkRange(long start, long end) {
        if (start < 0 || end > UNIVERSE) {
            throw new IllegalArgumentException("range [" + start + ", " + end + ") outside [0, 2^32]");
        }
    }

    private void rangeOp(long start, long end, char mode) {
        checkRange(start, end);
        if (start >= end) return;
        int k0 = (int) (start >>> 16), k1 = (int) ((end - 1) >>> 16);
        if (mode == '-') {
            for (int k = k1; k >= k0; k--) rangeOneKey(k, start, end, k0, k1, mode);   // descending: cheap removals
        } else {
            for (int k = k0; k <= k1; k++) rangeOneKey(k, start, end, k0, k1, mode);   // ascending: cheap appends
        }
    }

    private void rangeOneKey(int k, long start, long end, int k0, int k1, char mode) {
        int lo = k == k0 ? (int) (start & 0xFFFF) : 0;
        int hi = k == k1 ? (int) ((end - 1) & 0xFFFF) : 0xFFFF;
        int idx = find(k);
        Container c = idx >= 0 ? cs[idx] : null;
        Container res;
        if (c == null) {
            res = mode == '-' ? null : Container.runOf(lo, hi);
        } else if (mode == '-' && lo == 0 && hi == 0xFFFF) {
            res = null;
        } else {
            long[] w = c.words();
            Container.applyRange(w, lo, hi, mode);
            res = Container.fromWords(w);
        }
        if (c != null || res != null) store(k, idx, res);
    }

    /** Adds every value in {@code [start, endExclusive)}; bounds are in {@code [0, 2^32]}. */
    public void addRange(long start, long endExclusive) {
        rangeOp(start, endExclusive, '+');
    }

    public void removeRange(long start, long endExclusive) {
        rangeOp(start, endExclusive, '-');
    }

    /** Toggles membership of every value in {@code [start, endExclusive)}. */
    public void flip(long start, long endExclusive) {
        rangeOp(start, endExclusive, '^');
    }

    // ---------------- queries ----------------

    /** Number of elements {@code <= x} (unsigned). */
    public long rank(int x) {
        int key = x >>> 16;
        long r = 0;
        for (int i = 0; i < n && keys[i] <= key; i++) {
            r += keys[i] < key ? cs[i].cardinality() : cs[i].rank(x & 0xFFFF);
        }
        return r;
    }

    /** The i-th smallest element (0-based). */
    public int select(long i) {
        if (i < 0) throw new IndexOutOfBoundsException("select " + i);
        long left = i;
        for (int k = 0; k < n; k++) {
            int c = cs[k].cardinality();
            if (left < c) return (keys[k] << 16) | cs[k].select((int) left);
            left -= c;
        }
        throw new IndexOutOfBoundsException("select " + i + " but cardinality is " + cardinality());
    }

    public int first() {
        if (n == 0) throw new NoSuchElementException("empty bitmap");
        return (keys[0] << 16) | cs[0].first();
    }

    public int last() {
        if (n == 0) throw new NoSuchElementException("empty bitmap");
        return (keys[n - 1] << 16) | cs[n - 1].last();
    }

    /** Visits every element in unsigned ascending order. */
    public void forEach(IntConsumer consumer) {
        for (int i = 0; i < n; i++) {
            int hi = keys[i] << 16;
            cs[i].forEach(low -> consumer.accept(hi | low));
        }
    }

    public int[] toArray() {
        long card = cardinality();
        if (card > Integer.MAX_VALUE) throw new IllegalStateException("too many elements: " + card);
        int[] out = new int[(int) card];
        int[] pos = {0};
        forEach(v -> out[pos[0]++] = v);
        return out;
    }

    public RoaringBitmap copy() {
        RoaringBitmap b = new RoaringBitmap();
        b.keys = Arrays.copyOf(keys, Math.max(4, n));
        b.cs = new Container[b.keys.length];
        for (int i = 0; i < n; i++) b.cs[i] = cs[i].copy();
        b.n = n;
        return b;
    }

    /** Recompresses every container into its smallest encoding (run containers where strictly smaller). */
    public void runOptimize() {
        for (int i = 0; i < n; i++) cs[i] = Container.optimize(cs[i]);
    }

    // ---------------- set algebra ----------------

    private enum Op { AND, OR, XOR, ANDNOT }

    private static RoaringBitmap combine(RoaringBitmap a, RoaringBitmap b, Op op) {
        RoaringBitmap out = new RoaringBitmap();
        int i = 0, j = 0;
        while (i < a.n || j < b.n) {
            int ka = i < a.n ? a.keys[i] : Integer.MAX_VALUE;
            int kb = j < b.n ? b.keys[j] : Integer.MAX_VALUE;
            if (ka < kb) {
                if (op != Op.AND) out.insertAt(out.n, ka, Container.plain(a.cs[i]));
                i++;
            } else if (kb < ka) {
                if (op == Op.OR || op == Op.XOR) out.insertAt(out.n, kb, Container.plain(b.cs[j]));
                j++;
            } else {
                Container c = switch (op) {
                    case AND -> Container.and(a.cs[i], b.cs[j]);
                    case OR -> Container.or(a.cs[i], b.cs[j]);
                    case XOR -> Container.xor(a.cs[i], b.cs[j]);
                    case ANDNOT -> Container.andNot(a.cs[i], b.cs[j]);
                };
                if (c != null) out.insertAt(out.n, ka, c);
                i++;
                j++;
            }
        }
        return out;
    }

    public static RoaringBitmap and(RoaringBitmap a, RoaringBitmap b) { return combine(a, b, Op.AND); }

    public static RoaringBitmap or(RoaringBitmap a, RoaringBitmap b) { return combine(a, b, Op.OR); }

    public static RoaringBitmap xor(RoaringBitmap a, RoaringBitmap b) { return combine(a, b, Op.XOR); }

    public static RoaringBitmap andNot(RoaringBitmap a, RoaringBitmap b) { return combine(a, b, Op.ANDNOT); }

    // ---------------- equality ----------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoaringBitmap other) || other.n != n) return false;
        for (int i = 0; i < n; i++) {
            if (keys[i] != other.keys[i] || !Container.sameContent(cs[i], other.cs[i])) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (int i = 0; i < n; i++) h = 31 * (31 * h + keys[i]) + Arrays.hashCode(cs[i].words());
        return h;
    }

    @Override
    public String toString() {
        return "RoaringBitmap{card=" + cardinality() + ", containers=" + containerKinds() + "}";
    }

    // ---------------- introspection (tests) ----------------

    int containerCount() {
        return n;
    }

    List<String> containerKinds() {
        List<String> kinds = new ArrayList<>();
        for (int i = 0; i < n; i++) kinds.add(cs[i].kind());
        return kinds;
    }

    /** Throws IllegalStateException when any structural invariant is broken. */
    void checkInvariants() {
        for (int i = 0; i < n; i++) {
            if (keys[i] < 0 || keys[i] > 0xFFFF) throw new IllegalStateException("key out of range: " + keys[i]);
            if (i > 0 && keys[i - 1] >= keys[i]) throw new IllegalStateException("keys not strictly increasing at " + i);
            cs[i].check();
        }
    }

    // ---------------- serialisation ----------------

    public byte[] serialize() {
        int size = HEADER_SIZE + CRC_SIZE;
        for (int i = 0; i < n; i++) size += 3 + payloadSize(cs[i]);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(MAGIC).put((byte) VERSION).putInt(n);
        for (int i = 0; i < n; i++) {
            buf.putShort((short) keys[i]);
            Container c = cs[i];
            if (c instanceof Container.Array a) {
                buf.put((byte) 0).putInt(a.a.length);
                for (char v : a.a) buf.putShort((short) v);
            } else if (c instanceof Container.Bitmap bm) {
                buf.put((byte) 1);
                for (long w : bm.w) buf.putLong(w);
            } else {
                Container.Run r = (Container.Run) c;
                buf.put((byte) 2).putInt(r.d.length / 2);
                for (char v : r.d) buf.putShort((short) v);
            }
        }
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, buf.position());
        buf.putInt((int) crc.getValue());
        return buf.array();
    }

    private static int payloadSize(Container c) {
        if (c instanceof Container.Array a) return 4 + 2 * a.a.length;
        if (c instanceof Container.Bitmap) return 8 * Container.WORDS;
        return 4 + ((Container.Run) c).d.length * 2;
    }

    public static RoaringBitmap deserialize(byte[] image) {
        if (image.length < HEADER_SIZE + CRC_SIZE) throw new CorruptBitmapException("image truncated: " + image.length + " bytes");
        int bodyLen = image.length - CRC_SIZE;
        CRC32 crc = new CRC32();
        crc.update(image, 0, bodyLen);
        int stored = ByteBuffer.wrap(image, bodyLen, CRC_SIZE).getInt();
        if (stored != (int) crc.getValue()) throw new CorruptBitmapException("checksum mismatch");
        ByteBuffer in = ByteBuffer.wrap(image, 0, bodyLen);
        try {
            RoaringBitmap out = parse(in);
            if (in.hasRemaining()) throw new CorruptBitmapException("trailing bytes after last container");
            return out;
        } catch (BufferUnderflowException e) {
            throw new CorruptBitmapException("image truncated inside a container");
        }
    }

    private static RoaringBitmap parse(ByteBuffer in) {
        if (in.getInt() != MAGIC) throw new CorruptBitmapException("bad magic");
        int version = in.get();
        if (version != VERSION) throw new CorruptBitmapException("unsupported version " + version);
        int count = in.getInt();
        if (count < 0 || count > 65536) throw new CorruptBitmapException("bad container count " + count);
        RoaringBitmap out = new RoaringBitmap();
        int prevKey = -1;
        for (int i = 0; i < count; i++) {
            int key = in.getShort() & 0xFFFF;
            if (key <= prevKey) throw new CorruptBitmapException("keys not strictly increasing at container " + i);
            prevKey = key;
            int kind = in.get();
            Container c;
            switch (kind) {
                case 0 -> c = parseArray(in);
                case 1 -> c = parseBitmap(in);
                case 2 -> c = parseRun(in);
                default -> throw new CorruptBitmapException("unknown container kind " + kind);
            }
            out.insertAt(out.n, key, c);
        }
        return out;
    }

    private static Container parseArray(ByteBuffer in) {
        int card = in.getInt();
        if (card <= 0) throw new CorruptBitmapException("empty array container");
        if (card > Container.ARRAY_MAX) throw new CorruptBitmapException("array container with " + card + " values");
        if (in.remaining() < 2 * card) throw new BufferUnderflowException();
        char[] a = new char[card];
        for (int i = 0; i < card; i++) {
            a[i] = in.getChar();
            if (i > 0 && a[i - 1] >= a[i]) throw new CorruptBitmapException("array values not strictly increasing");
        }
        return new Container.Array(a);
    }

    private static Container parseBitmap(ByteBuffer in) {
        if (in.remaining() < 8 * Container.WORDS) throw new BufferUnderflowException();
        long[] w = new long[Container.WORDS];
        int card = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = in.getLong();
            card += Long.bitCount(w[i]);
        }
        if (card <= Container.ARRAY_MAX) throw new CorruptBitmapException("bitmap container with only " + card + " values");
        return new Container.Bitmap(w, card);
    }

    private static Container parseRun(ByteBuffer in) {
        int runs = in.getInt();
        if (runs <= 0) throw new CorruptBitmapException("empty run container");
        if (runs > 32768) throw new CorruptBitmapException("run container with " + runs + " runs");
        if (in.remaining() < 4 * runs) throw new BufferUnderflowException();
        char[] d = new char[2 * runs];
        int card = 0;
        int prevEnd = -2;
        for (int i = 0; i < runs; i++) {
            int start = in.getChar(), lenMinus1 = in.getChar();
            if (start <= prevEnd + 1) throw new CorruptBitmapException("run container: runs overlap, touch or are out of order");
            int end = start + lenMinus1;
            if (end > 0xFFFF) throw new CorruptBitmapException("run container: run extends past 65535");
            d[2 * i] = (char) start;
            d[2 * i + 1] = (char) lenMinus1;
            card += lenMinus1 + 1;
            prevEnd = end;
        }
        return new Container.Run(d, card);
    }
}
