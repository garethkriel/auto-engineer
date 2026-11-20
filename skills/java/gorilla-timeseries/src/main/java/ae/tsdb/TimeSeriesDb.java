package ae.tsdb;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * In-memory time-series store. Each series keeps an uncompressed head and a list of sealed, Gorilla-compressed
 * blocks. Not thread-safe.
 */
public final class TimeSeriesDb {
    private static final byte[] MAGIC = {'T', 'S', 'D', 'B'};
    private static final byte VERSION = 1;

    private record Block(long min, long max, int count, byte[] data) { }

    private static final class Series {
        final List<Block> blocks = new ArrayList<>();
        final List<Point> head = new ArrayList<>();
        long last = -1;
    }

    private final int blockSize;
    private final TreeMap<String, Series> series = new TreeMap<>();
    private long blocksDecoded;

    /** @param blockSize number of points after which the head is sealed into a compressed block (>= 1) */
    public TimeSeriesDb(int blockSize) {
        if (blockSize < 1) throw new IllegalArgumentException("blockSize must be >= 1");
        this.blockSize = blockSize;
    }

    /**
     * Appends a sample. All validation happens before any state changes.
     *
     * @throws IllegalArgumentException for an empty name, negative ts, or ts not after the series' latest ts
     */
    public void append(String name, long ts, double value) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("empty series name");
        if (ts < 0) throw new IllegalArgumentException("negative timestamp");
        Series s = series.get(name);
        if (s != null && ts <= s.last) throw new IllegalArgumentException("timestamp " + ts + " not after " + s.last);
        if (s == null) {
            s = new Series();
            series.put(name, s);
        }
        s.head.add(new Point(ts, value));
        s.last = ts;
        if (s.head.size() >= blockSize) seal(s);
    }

    private static void seal(Series s) {
        List<Point> h = s.head;
        s.blocks.add(new Block(h.get(0).ts(), h.get(h.size() - 1).ts(), h.size(), GorillaCodec.encode(h)));
        h.clear();
    }

    /** @return the points of {@code name} with {@code from <= ts < to}, in time order */
    public List<Point> query(String name, long from, long to) {
        Series s = series.get(name);
        List<Point> out = new ArrayList<>();
        if (s == null || from >= to) return out;
        for (Block b : s.blocks) {
            if (b.max() < from || b.min() >= to) continue;
            blocksDecoded++;
            for (Point p : GorillaCodec.decode(b.data())) if (p.ts() >= from && p.ts() < to) out.add(p);
        }
        for (Point p : s.head) if (p.ts() >= from && p.ts() < to) out.add(p);
        return out;
    }

    /**
     * Aggregates {@code [from, to)} into buckets of width {@code step} aligned to {@code from}. Only non-empty buckets
     * are returned; NaN points are ignored. The last bucket is truncated at {@code to}.
     */
    public List<Bucket> aggregate(String name, long from, long to, long step, Agg agg) {
        if (step <= 0) throw new IllegalArgumentException("step must be > 0");
        Objects.requireNonNull(agg, "agg");
        List<Bucket> out = new ArrayList<>();
        long curStart = 0;
        int n = 0;
        double sum = 0, min = 0, max = 0, first = 0, last = 0;
        for (Point p : query(name, from, to)) {
            if (Double.isNaN(p.value())) continue;
            long start = from + (p.ts() - from) / step * step;
            if (n > 0 && start != curStart) {
                out.add(new Bucket(curStart, n, result(agg, n, sum, min, max, first, last)));
                n = 0;
            }
            if (n == 0) {
                curStart = start;
                sum = 0;
                min = max = first = p.value();
            }
            n++;
            sum += p.value();
            min = Math.min(min, p.value());
            max = Math.max(max, p.value());
            last = p.value();
        }
        if (n > 0) out.add(new Bucket(curStart, n, result(agg, n, sum, min, max, first, last)));
        return out;
    }

    private static double result(Agg agg, int n, double sum, double min, double max, double first, double last) {
        return switch (agg) {
            case COUNT -> n;
            case SUM -> sum;
            case MIN -> min;
            case MAX -> max;
            case AVG -> sum / n;
            case FIRST -> first;
            case LAST -> last;
        };
    }

    /** @return the newest point of the series, if any */
    public Optional<Point> latest(String name) {
        Series s = series.get(name);
        if (s == null) return Optional.empty();
        if (!s.head.isEmpty()) return Optional.of(s.head.get(s.head.size() - 1));
        if (s.blocks.isEmpty()) return Optional.empty();
        List<Point> pts = GorillaCodec.decode(s.blocks.get(s.blocks.size() - 1).data());
        return Optional.of(pts.get(pts.size() - 1));
    }

    /**
     * Retention: drops sealed blocks whose newest timestamp is below {@code cutoff}, in every series. The head is never
     * dropped, and a block straddling the cutoff is kept whole.
     *
     * @return number of points dropped
     */
    public long retain(long cutoff) {
        long dropped = 0;
        for (Series s : series.values()) {
            for (Iterator<Block> it = s.blocks.iterator(); it.hasNext();) {
                Block b = it.next();
                if (b.max() < cutoff) {
                    dropped += b.count();
                    it.remove();
                }
            }
        }
        return dropped;
    }

    /** @return series names in sorted order */
    public List<String> seriesNames() {
        return new ArrayList<>(series.keySet());
    }

    /** @return number of stored points in the series (0 if unknown) */
    public long pointCount(String name) {
        Series s = series.get(name);
        if (s == null) return 0;
        long n = s.head.size();
        for (Block b : s.blocks) n += b.count();
        return n;
    }

    /** @return number of sealed blocks of the series */
    public int blockCount(String name) {
        Series s = series.get(name);
        return s == null ? 0 : s.blocks.size();
    }

    /** @return total bytes of the series' sealed blocks */
    public long compressedBytes(String name) {
        Series s = series.get(name);
        long n = 0;
        if (s != null) for (Block b : s.blocks) n += b.data().length;
        return n;
    }

    /** @return how many blocks queries have decoded since the last reset (test hook) */
    public long blocksDecoded() {
        return blocksDecoded;
    }

    /** Resets {@link #blocksDecoded()}. */
    public void resetDecodeCount() {
        blocksDecoded = 0;
    }

    // ---------------------------------------------------------------- snapshot

    /** @return a canonical image of the whole database with a CRC32 trailer */
    public byte[] snapshot() {
        int size = 4 + 1 + 4 + 4 + 4;
        for (Map.Entry<String, Series> e : series.entrySet()) {
            size += 2 + e.getKey().getBytes(StandardCharsets.UTF_8).length + 4 + 4;
            for (Block b : e.getValue().blocks) size += 4 + 8 + 8 + 4 + b.data().length;
            size += e.getValue().head.size() * 16;
        }
        ByteBuffer bb = ByteBuffer.allocate(size);
        bb.put(MAGIC).put(VERSION).putInt(blockSize).putInt(series.size());
        for (Map.Entry<String, Series> e : series.entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            if (name.length > Short.MAX_VALUE) throw new IllegalStateException("series name too long");
            bb.putShort((short) name.length).put(name);
            Series s = e.getValue();
            bb.putInt(s.blocks.size());
            for (Block b : s.blocks) {
                bb.putInt(b.count()).putLong(b.min()).putLong(b.max()).putInt(b.data().length).put(b.data());
            }
            bb.putInt(s.head.size());
            for (Point p : s.head) bb.putLong(p.ts()).putLong(Double.doubleToRawLongBits(p.value()));
        }
        CRC32 crc = new CRC32();
        crc.update(bb.array(), 0, bb.position());
        bb.putInt((int) crc.getValue());
        return bb.array();
    }

    /**
     * Rebuilds a database from {@link #snapshot()} output.
     *
     * @throws CorruptDataException if the CRC fails or the structure is invalid
     */
    public static TimeSeriesDb restore(byte[] image) {
        if (image.length < 4 + 1 + 4 + 4 + 4) throw new CorruptDataException("image too short");
        ByteBuffer all = ByteBuffer.wrap(image);
        CRC32 crc = new CRC32();
        crc.update(image, 0, image.length - 4);
        if ((int) crc.getValue() != all.getInt(image.length - 4)) throw new CorruptDataException("checksum mismatch");
        ByteBuffer bb = ByteBuffer.wrap(image, 0, image.length - 4);
        try {
            return parse(bb);
        } catch (BufferUnderflowException | IllegalArgumentException | NegativeArraySizeException e) {
            throw new CorruptDataException("malformed image: " + e);
        }
    }

    private static TimeSeriesDb parse(ByteBuffer bb) {
        for (byte m : MAGIC) if (bb.get() != m) throw new CorruptDataException("bad magic");
        if (bb.get() != VERSION) throw new CorruptDataException("unsupported version");
        int blockSize = bb.getInt();
        if (blockSize < 1) throw new CorruptDataException("bad block size");
        TimeSeriesDb db = new TimeSeriesDb(blockSize);
        int nSeries = bb.getInt();
        String prevName = null;
        for (int i = 0; i < nSeries; i++) {
            int nameLen = bb.getShort();
            if (nameLen <= 0 || nameLen > bb.remaining()) throw new CorruptDataException("bad name length");
            byte[] nb = new byte[nameLen];
            bb.get(nb);
            String name = new String(nb, StandardCharsets.UTF_8);
            if (prevName != null && name.compareTo(prevName) <= 0) throw new CorruptDataException("series out of order");
            prevName = name;
            Series s = new Series();
            int nBlocks = bb.getInt();
            if (nBlocks < 0 || nBlocks > bb.remaining()) throw new CorruptDataException("bad block count");
            for (int j = 0; j < nBlocks; j++) {
                int count = bb.getInt();
                long min = bb.getLong();
                long max = bb.getLong();
                int len = bb.getInt();
                if (len < 0 || len > bb.remaining()) throw new CorruptDataException("bad block length");
                byte[] data = new byte[len];
                bb.get(data);
                List<Point> pts = GorillaCodec.decode(data);
                if (pts.size() != count || count < 1) throw new CorruptDataException("block count mismatch");
                if (pts.get(0).ts() != min) throw new CorruptDataException("block min mismatch");
                if (pts.get(count - 1).ts() != max) throw new CorruptDataException("block max mismatch");
                if (min <= s.last) throw new CorruptDataException("blocks out of order");
                s.blocks.add(new Block(min, max, count, data));
                s.last = max;
            }
            int nHead = bb.getInt();
            if (nHead < 0 || nHead >= blockSize || (long) nHead * 16 > bb.remaining()) {
                throw new CorruptDataException("bad head size");
            }
            for (int j = 0; j < nHead; j++) {
                long ts = bb.getLong();
                double v = Double.longBitsToDouble(bb.getLong());
                if (ts < 0 || ts <= s.last) throw new CorruptDataException("head out of order");
                s.head.add(new Point(ts, v));
                s.last = ts;
            }
            db.series.put(name, s);
        }
        if (bb.hasRemaining()) throw new CorruptDataException("trailing bytes");
        return db;
    }
}
