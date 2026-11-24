package ae.columnar;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * An immutable, non-empty column segment of {@code long} values stored in one of five {@link Encoding}s.
 *
 * <p>Serialised layout (big-endian): {@code n:int, encoding:byte, payload, crc32:int} where the CRC covers every
 * preceding byte. Payloads:
 * <ul>
 * <li>PLAIN: n longs.</li>
 * <li>RLE: {@code runs:int}, then per run {@code value:long, length:int}.</li>
 * <li>DICT: {@code size:int}, sorted distinct longs, {@code width:byte}, packed ids.</li>
 * <li>FOR: {@code base:long, width:byte}, packed {@code value-base} (unsigned).</li>
 * <li>DELTA: {@code first:long, width:byte}, packed zigzag of the wrapping differences (n-1 of them).</li>
 * </ul>
 * Packing widths are canonical (the smallest that fits) and padding bits must be zero.
 */
public final class EncodedColumn {
    private static final int CHECKPOINT = 64;
    private static final int HEADER = 5;
    private static final int TRAILER = 4;
    /** Upper bound on rows accepted by {@link #fromBytes}: width-0 images are tiny but decode to n longs. */
    public static final int MAX_ROWS = 1 << 24;

    private final Encoding enc;
    private final int n;
    private final long min;
    private final long max;
    // PLAIN
    private final long[] plain;
    // RLE
    private final long[] runValues;
    private final int[] runEnds; // exclusive cumulative end of each run
    // DICT
    private final long[] dict;
    // DICT / FOR / DELTA
    private final long[] packed;
    private final int width;
    // FOR base / DELTA first
    private final long anchor;
    // DELTA: value at every CHECKPOINT-th index
    private final long[] checkpoints;

    private EncodedColumn(Encoding enc, int n, long min, long max, long[] plain, long[] runValues, int[] runEnds,
                          long[] dict, long[] packed, int width, long anchor, long[] checkpoints) {
        this.enc = enc;
        this.n = n;
        this.min = min;
        this.max = max;
        this.plain = plain;
        this.runValues = runValues;
        this.runEnds = runEnds;
        this.dict = dict;
        this.packed = packed;
        this.width = width;
        this.anchor = anchor;
        this.checkpoints = checkpoints;
    }

    // ------------------------------------------------------------------ construction

    /** Encodes with the given encoding. */
    public static EncodedColumn encode(long[] values, Encoding e) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(e, "encoding");
        if (values.length == 0) {
            throw new IllegalArgumentException("empty column");
        }
        long lo = values[0];
        long hi = values[0];
        for (long v : values) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        int n = values.length;
        switch (e) {
            case PLAIN:
                return new EncodedColumn(e, n, lo, hi, values.clone(), null, null, null, null, 0, 0, null);
            case RLE: {
                int runs = 1;
                for (int i = 1; i < n; i++) {
                    if (values[i] != values[i - 1]) {
                        runs++;
                    }
                }
                long[] rv = new long[runs];
                int[] re = new int[runs];
                int r = 0;
                rv[0] = values[0];
                for (int i = 1; i < n; i++) {
                    if (values[i] != values[i - 1]) {
                        re[r++] = i;
                        rv[r] = values[i];
                    }
                }
                re[r] = n;
                return new EncodedColumn(e, n, lo, hi, null, rv, re, null, null, 0, 0, null);
            }
            case DICT: {
                long[] sorted = values.clone();
                Arrays.sort(sorted);
                int d = 0;
                for (int i = 0; i < n; i++) {
                    if (i == 0 || sorted[i] != sorted[i - 1]) {
                        sorted[d++] = sorted[i];
                    }
                }
                long[] dict = Arrays.copyOf(sorted, d);
                long[] ids = new long[n];
                for (int i = 0; i < n; i++) {
                    ids[i] = Arrays.binarySearch(dict, values[i]);
                }
                int w = BitPacking.widthFor(d - 1);
                return new EncodedColumn(e, n, lo, hi, null, null, null, dict, BitPacking.pack(ids, w), w, 0, null);
            }
            case FOR: {
                long[] off = new long[n];
                long maxOff = 0;
                for (int i = 0; i < n; i++) {
                    off[i] = values[i] - lo; // wraps modulo 2^64, correct as unsigned
                    if (Long.compareUnsigned(off[i], maxOff) > 0) {
                        maxOff = off[i];
                    }
                }
                int w = BitPacking.widthFor(maxOff);
                return new EncodedColumn(e, n, lo, hi, null, null, null, null, BitPacking.pack(off, w), w, lo, null);
            }
            case DELTA: {
                long[] z = new long[n - 1];
                long maxZ = 0;
                for (int i = 1; i < n; i++) {
                    z[i - 1] = zigzag(values[i] - values[i - 1]);
                    if (Long.compareUnsigned(z[i - 1], maxZ) > 0) {
                        maxZ = z[i - 1];
                    }
                }
                int w = BitPacking.widthFor(maxZ);
                long[] packed = BitPacking.pack(z, w);
                return new EncodedColumn(e, n, lo, hi, null, null, null, null, packed, w, values[0],
                    checkpointsOf(values[0], packed, w, n));
            }
            default:
                throw new AssertionError(e);
        }
    }

    /** Encodes with every encoding and keeps the smallest serialised form; ties go to the earlier enum value. */
    public static EncodedColumn encodeBest(long[] values) {
        EncodedColumn best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Encoding e : Encoding.values()) {
            EncodedColumn c = encode(values, e);
            int len = c.toBytes().length;
            if (len < bestLen) {
                best = c;
                bestLen = len;
            }
        }
        return best;
    }

    private static long zigzag(long d) {
        return (d << 1) ^ (d >> 63);
    }

    private static long unzigzag(long z) {
        return (z >>> 1) ^ -(z & 1);
    }

    private static long[] checkpointsOf(long first, long[] packed, int width, int n) {
        long[] cp = new long[(n + CHECKPOINT - 1) / CHECKPOINT];
        long v = first;
        cp[0] = v;
        for (int i = 1; i < n; i++) {
            v += unzigzag(BitPacking.get(packed, width, i - 1));
            if (i % CHECKPOINT == 0) {
                cp[i / CHECKPOINT] = v;
            }
        }
        return cp;
    }

    // ------------------------------------------------------------------ access

    public Encoding encoding() {
        return enc;
    }

    public int size() {
        return n;
    }

    /** Smallest value (zone map). */
    public long min() {
        return min;
    }

    /** Largest value (zone map). */
    public long max() {
        return max;
    }

    /** Random access to the {@code i}-th value. */
    public long get(int i) {
        if (i < 0 || i >= n) {
            throw new IndexOutOfBoundsException("index " + i + " size " + n);
        }
        switch (enc) {
            case PLAIN:
                return plain[i];
            case RLE: {
                int lo = 0;
                int hi = runEnds.length - 1;
                while (lo < hi) { // first run whose end is > i
                    int mid = (lo + hi) >>> 1;
                    if (runEnds[mid] > i) {
                        hi = mid;
                    } else {
                        lo = mid + 1;
                    }
                }
                return runValues[lo];
            }
            case DICT:
                return dict[(int) BitPacking.get(packed, width, i)];
            case FOR:
                return anchor + BitPacking.get(packed, width, i);
            case DELTA: {
                long v = checkpoints[i / CHECKPOINT];
                for (int j = i / CHECKPOINT * CHECKPOINT + 1; j <= i; j++) {
                    v += unzigzag(BitPacking.get(packed, width, j - 1));
                }
                return v;
            }
            default:
                throw new AssertionError(enc);
        }
    }

    /** All values in order. */
    public long[] decode() {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = get(i);
        }
        return out;
    }

    /** Row indexes (bits) whose value satisfies {@code value op probe}. Always returns a fresh set. */
    public BitSet scan(Op op, long probe) {
        BitSet r = new BitSet(n);
        switch (op.prune(min, max, probe)) {
            case NONE:
                return r;
            case ALL:
                r.set(0, n);
                return r;
            default:
                break;
        }
        switch (enc) {
            case PLAIN:
                for (int i = 0; i < n; i++) {
                    if (op.test(plain[i], probe)) {
                        r.set(i);
                    }
                }
                break;
            case RLE: {
                int start = 0;
                for (int k = 0; k < runValues.length; k++) {
                    if (op.test(runValues[k], probe)) {
                        r.set(start, runEnds[k]);
                    }
                    start = runEnds[k];
                }
                break;
            }
            case DICT: {
                boolean[] hit = new boolean[dict.length];
                for (int k = 0; k < dict.length; k++) {
                    hit[k] = op.test(dict[k], probe);
                }
                for (int i = 0; i < n; i++) {
                    if (hit[(int) BitPacking.get(packed, width, i)]) {
                        r.set(i);
                    }
                }
                break;
            }
            case FOR:
                for (int i = 0; i < n; i++) {
                    if (op.test(anchor + BitPacking.get(packed, width, i), probe)) {
                        r.set(i);
                    }
                }
                break;
            case DELTA: {
                long v = anchor;
                if (op.test(v, probe)) {
                    r.set(0);
                }
                for (int i = 1; i < n; i++) {
                    v += unzigzag(BitPacking.get(packed, width, i - 1));
                    if (op.test(v, probe)) {
                        r.set(i);
                    }
                }
                break;
            }
            default:
                throw new AssertionError(enc);
        }
        return r;
    }

    // ------------------------------------------------------------------ serialisation

    /** Serialises to the documented layout, CRC32 trailer included. */
    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(bos)) {
            o.writeInt(n);
            o.writeByte(enc.ordinal());
            switch (enc) {
                case PLAIN:
                    for (long v : plain) {
                        o.writeLong(v);
                    }
                    break;
                case RLE: {
                    o.writeInt(runValues.length);
                    int start = 0;
                    for (int k = 0; k < runValues.length; k++) {
                        o.writeLong(runValues[k]);
                        o.writeInt(runEnds[k] - start);
                        start = runEnds[k];
                    }
                    break;
                }
                case DICT:
                    o.writeInt(dict.length);
                    for (long d : dict) {
                        o.writeLong(d);
                    }
                    writePacked(o);
                    break;
                case FOR:
                case DELTA:
                    o.writeLong(anchor);
                    writePacked(o);
                    break;
                default:
                    throw new AssertionError(enc);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        byte[] body = bos.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body);
        return ByteBuffer.allocate(body.length + TRAILER).put(body).putInt((int) crc.getValue()).array();
    }

    private void writePacked(DataOutputStream o) throws IOException {
        o.writeByte(width);
        for (long w : packed) {
            o.writeLong(w);
        }
    }

    /**
     * Parses and validates a serialised column.
     *
     * @throws IllegalArgumentException for any malformed, truncated, corrupt or non-canonical input
     */
    public static EncodedColumn fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < HEADER + TRAILER) {
            throw new IllegalArgumentException("too short: " + bytes.length);
        }
        int bodyLen = bytes.length - TRAILER;
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bodyLen);
        if ((int) crc.getValue() != ByteBuffer.wrap(bytes, bodyLen, TRAILER).getInt()) {
            throw new IllegalArgumentException("checksum mismatch");
        }
        ByteBuffer b = ByteBuffer.wrap(bytes, 0, bodyLen);
        try {
            int n = b.getInt();
            if (n < 1 || n > MAX_ROWS) {
                throw new IllegalArgumentException("bad row count " + n);
            }
            int tag = b.get();
            if (tag < 0 || tag >= Encoding.values().length) {
                throw new IllegalArgumentException("unknown encoding " + tag);
            }
            Encoding e = Encoding.values()[tag];
            switch (e) {
                case PLAIN: {
                    expectRemaining(b, 8L * n, "PLAIN payload");
                    long[] v = new long[n];
                    for (int i = 0; i < n; i++) {
                        v[i] = b.getLong();
                    }
                    return encode(v, Encoding.PLAIN);
                }
                case RLE: {
                    int runs = b.getInt();
                    if (runs < 1 || runs > n) {
                        throw new IllegalArgumentException("bad run count " + runs);
                    }
                    expectRemaining(b, 12L * runs, "RLE payload");
                    long[] values = new long[runs];
                    long total = 0;
                    for (int k = 0; k < runs; k++) {
                        values[k] = b.getLong();
                        int len = b.getInt();
                        if (len < 1) {
                            throw new IllegalArgumentException("bad run length " + len);
                        }
                        total += len;
                    }
                    if (total != n) {
                        throw new IllegalArgumentException("runs cover " + total + " rows, expected " + n);
                    }
                    long[] expanded = new long[n];
                    b.position(HEADER + 4);
                    int at = 0;
                    for (int k = 0; k < runs; k++) {
                        b.getLong();
                        int len = b.getInt();
                        Arrays.fill(expanded, at, at + len, values[k]);
                        at += len;
                    }
                    return encode(expanded, Encoding.RLE);
                }
                case DICT: {
                    int d = b.getInt();
                    if (d < 1 || d > n) {
                        throw new IllegalArgumentException("bad dictionary size " + d);
                    }
                    if (b.remaining() < 8L * d + 1) {
                        throw new IllegalArgumentException("truncated dictionary");
                    }
                    long[] dict = new long[d];
                    for (int k = 0; k < d; k++) {
                        dict[k] = b.getLong();
                        if (k > 0 && dict[k] <= dict[k - 1]) {
                            throw new IllegalArgumentException("dictionary not strictly ascending");
                        }
                    }
                    int w = b.get();
                    if (w != BitPacking.widthFor(d - 1)) {
                        throw new IllegalArgumentException("non-canonical dictionary id width " + w);
                    }
                    long[] words = readWords(b, n, w);
                    long[] values = new long[n];
                    for (int i = 0; i < n; i++) {
                        long id = BitPacking.get(words, w, i);
                        if (id >= d) {
                            throw new IllegalArgumentException("dictionary id " + id + " out of range");
                        }
                        values[i] = dict[(int) id];
                    }
                    return encode(values, Encoding.DICT);
                }
                case FOR: {
                    long base = b.getLong();
                    int w = b.get();
                    long[] words = readWords(b, n, w);
                    long[] values = new long[n];
                    long maxOff = 0;
                    for (int i = 0; i < n; i++) {
                        long off = BitPacking.get(words, w, i);
                        if (Long.compareUnsigned(off, maxOff) > 0) {
                            maxOff = off;
                        }
                        values[i] = base + off;
                    }
                    if (w != BitPacking.widthFor(maxOff)) {
                        throw new IllegalArgumentException("non-canonical FOR width " + w);
                    }
                    EncodedColumn c = encode(values, Encoding.FOR);
                    if (c.anchor != base) {
                        throw new IllegalArgumentException("FOR base is not the minimum");
                    }
                    return c;
                }
                case DELTA: {
                    long first = b.getLong();
                    int w = b.get();
                    long[] words = readWords(b, n - 1, w);
                    long[] values = new long[n];
                    values[0] = first;
                    long maxZ = 0;
                    for (int i = 1; i < n; i++) {
                        long z = BitPacking.get(words, w, i - 1);
                        if (Long.compareUnsigned(z, maxZ) > 0) {
                            maxZ = z;
                        }
                        values[i] = values[i - 1] + unzigzag(z);
                    }
                    if (w != BitPacking.widthFor(maxZ)) {
                        throw new IllegalArgumentException("non-canonical DELTA width " + w);
                    }
                    return encode(values, Encoding.DELTA);
                }
                default:
                    throw new AssertionError(e);
            }
        } catch (BufferUnderflowException ex) {
            throw new IllegalArgumentException("truncated image", ex);
        }
    }

    private static void expectRemaining(ByteBuffer b, long expected, String what) {
        if (b.remaining() != expected) {
            throw new IllegalArgumentException(what + ": expected " + expected + " bytes, found " + b.remaining());
        }
    }

    /** Reads exactly the words for {@code count} values of {@code w} bits, rejecting non-zero padding. */
    private static long[] readWords(ByteBuffer b, int count, int w) {
        if (w < 0 || w > 64) {
            throw new IllegalArgumentException("bad width " + w);
        }
        long wordCount = ((long) count * w + 63) >>> 6;
        expectRemaining(b, wordCount * 8, "packed payload");
        long[] words = new long[(int) wordCount];
        for (int i = 0; i < words.length; i++) {
            words[i] = b.getLong();
        }
        long usedBits = (long) count * w;
        if (words.length > 0 && (usedBits & 63) != 0 && (words[words.length - 1] >>> (usedBits & 63)) != 0) {
            throw new IllegalArgumentException("non-zero padding bits");
        }
        return words;
    }
}
