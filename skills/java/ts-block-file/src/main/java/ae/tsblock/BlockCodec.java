package ae.tsblock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes a non-empty run of points as: varint count, first ts, first value, then for each
 * further point the zigzag delta-of-delta of the timestamp and the zigzag delta of the value.
 * Subtractions wrap on overflow; decoding adds back with the same wrap, so every long round trips.
 */
final class BlockCodec {
    private BlockCodec() {}

    static byte[] encode(List<Point> pts) {
        if (pts.isEmpty()) {
            throw new IllegalArgumentException("empty block");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Varint.writeUnsigned(out, pts.size());
        Point first = pts.get(0);
        Varint.writeSigned(out, first.ts());
        Varint.writeSigned(out, first.value());
        long prevTs = first.ts();
        long prevValue = first.value();
        long prevDelta = 0;
        for (int i = 1; i < pts.size(); i++) {
            Point p = pts.get(i);
            long delta = p.ts() - prevTs;
            Varint.writeSigned(out, delta - prevDelta);
            Varint.writeSigned(out, p.value() - prevValue);
            prevTs = p.ts();
            prevValue = p.value();
            prevDelta = delta;
        }
        return out.toByteArray();
    }

    static List<Point> decode(ByteBuffer buf, int expectedCount) throws CorruptFileException {
        long count = Varint.readUnsigned(buf);
        if (count != expectedCount) {
            throw new CorruptFileException("block count " + count + " != indexed " + expectedCount);
        }
        List<Point> pts = new ArrayList<>(expectedCount);
        long ts = Varint.readSigned(buf);
        long value = Varint.readSigned(buf);
        pts.add(new Point(ts, value));
        long delta = 0;
        for (int i = 1; i < expectedCount; i++) {
            delta += Varint.readSigned(buf);
            ts += delta;
            value += Varint.readSigned(buf);
            pts.add(new Point(ts, value));
        }
        if (buf.hasRemaining()) {
            throw new CorruptFileException("trailing bytes in block");
        }
        return pts;
    }
}
