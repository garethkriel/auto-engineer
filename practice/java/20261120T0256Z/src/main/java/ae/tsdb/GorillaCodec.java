package ae.tsdb;

import java.util.ArrayList;
import java.util.List;

/**
 * Gorilla-style compression of a time-ordered series: delta-of-delta timestamps and XOR-ed double bits.
 * See the layout in the round's TASK.md. Values round-trip bit-exactly (raw bits, so NaN payloads survive).
 */
public final class GorillaCodec {
    private static final int COUNT_BITS = 32;

    private GorillaCodec() { }

    /**
     * Encodes points whose timestamps are non-negative and strictly increasing.
     *
     * @throws IllegalArgumentException on a bad timestamp
     */
    public static byte[] encode(List<Point> points) {
        long prev = -1;
        for (Point p : points) {
            if (p.ts() < 0 || p.ts() <= prev) throw new IllegalArgumentException("timestamps must be >= 0 and strictly increasing: " + p.ts());
            prev = p.ts();
        }
        BitWriter w = new BitWriter();
        w.writeBits(points.size(), COUNT_BITS);
        long prevTs = 0, prevDelta = 0, prevBits = 0;
        int lead = -1, trail = 0;
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            long bits = Double.doubleToRawLongBits(p.value());
            if (i == 0) {
                w.writeBits(p.ts(), 64);
                w.writeBits(bits, 64);
            } else {
                long delta = p.ts() - prevTs;
                writeDod(w, delta - prevDelta);
                prevDelta = delta;
                long xor = bits ^ prevBits;
                if (xor == 0) {
                    w.writeBit(0);
                } else {
                    w.writeBit(1);
                    int l = Math.min(Long.numberOfLeadingZeros(xor), 31);
                    int t = Long.numberOfTrailingZeros(xor);
                    if (lead >= 0 && l >= lead && t >= trail) {
                        w.writeBit(0);
                        writeMeaningful(w, xor, 64 - lead - trail, trail);
                    } else {
                        w.writeBit(1);
                        int len = 64 - l - t;
                        w.writeBits(l, 5);
                        w.writeBits(len - 1, 6);
                        writeMeaningful(w, xor, len, t);
                        lead = l;
                        trail = t;
                    }
                }
            }
            prevTs = p.ts();
            prevBits = bits;
        }
        return w.toByteArray();
    }

    private static void writeMeaningful(BitWriter w, long xor, int len, int trail) {
        long v = xor >>> trail;
        w.writeBits(len == 64 ? v : v & ((1L << len) - 1), len);
    }

    private static void writeDod(BitWriter w, long d) {
        if (d == 0) {
            w.writeBit(0);
        } else if (d >= -63 && d <= 64) {
            w.writeBits(0b10, 2);
            w.writeBits(d + 63, 7);
        } else if (d >= -255 && d <= 256) {
            w.writeBits(0b110, 3);
            w.writeBits(d + 255, 9);
        } else if (d >= -2047 && d <= 2048) {
            w.writeBits(0b1110, 4);
            w.writeBits(d + 2047, 12);
        } else {
            w.writeBits(0b1111, 4);
            w.writeBits(d, 64);
        }
    }

    /**
     * Decodes a series produced by {@link #encode}.
     *
     * @throws CorruptDataException if the data is truncated, malformed or has trailing bytes
     */
    public static List<Point> decode(byte[] data) {
        BitReader r = new BitReader(data);
        long count = r.readBits(COUNT_BITS);
        // every point after the first costs at least 2 bits, so a count this large cannot be genuine
        if (count > 1 && (count - 1) * 2 > r.remainingBits()) throw new CorruptDataException("count " + count + " exceeds data");
        if (count == 1 && r.remainingBits() < 128) throw new CorruptDataException("truncated first point");
        List<Point> out = new ArrayList<>((int) Math.min(count, 1 << 16));
        long prevTs = 0, prevDelta = 0, prevBits = 0;
        int lead = -1, trail = 0;
        for (long i = 0; i < count; i++) {
            long ts, bits;
            if (i == 0) {
                ts = r.readBits(64);
                bits = r.readBits(64);
                if (ts < 0) throw new CorruptDataException("negative timestamp");
            } else {
                long delta = prevDelta + readDod(r);
                ts = prevTs + delta;
                if (delta <= 0 || ts < 0 || ts <= prevTs) throw new CorruptDataException("timestamps not increasing");
                prevDelta = delta;
                bits = prevBits;
                if (r.readBit() == 1) {
                    if (r.readBit() == 1) {
                        lead = (int) r.readBits(5);
                        int len = (int) r.readBits(6) + 1;
                        trail = 64 - lead - len;
                        if (trail < 0) throw new CorruptDataException("bad xor window");
                    } else if (lead < 0) {
                        throw new CorruptDataException("xor window reused before being defined");
                    }
                    int len = 64 - lead - trail;
                    bits ^= r.readBits(len) << trail;
                }
            }
            out.add(new Point(ts, Double.longBitsToDouble(bits)));
            prevTs = ts;
            prevBits = bits;
        }
        if (r.remainingBits() > 7) throw new CorruptDataException("trailing data");
        if (r.remainingBits() > 0 && r.readBits((int) r.remainingBits()) != 0) throw new CorruptDataException("non-zero padding");
        return out;
    }

    private static long readDod(BitReader r) {
        if (r.readBit() == 0) return 0;
        if (r.readBit() == 0) return r.readBits(7) - 63;
        if (r.readBit() == 0) return r.readBits(9) - 255;
        if (r.readBit() == 0) return r.readBits(12) - 2047;
        return r.readBits(64);
    }
}
