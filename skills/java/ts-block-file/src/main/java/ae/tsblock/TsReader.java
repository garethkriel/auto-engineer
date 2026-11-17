package ae.tsblock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * Reads a file produced by {@link TsWriter}. Opening validates header, trailer and the footer
 * index (CRC); each block is CRC-checked only when it is decoded.
 */
public final class TsReader {
    private record Entry(long offset, long minTs, long maxTs, int count) {}

    private final byte[] data;
    private final List<Entry> entries;
    private final long total;
    private final AtomicInteger decoded = new AtomicInteger();

    private TsReader(byte[] data, List<Entry> entries, long total) {
        this.data = data;
        this.entries = entries;
        this.total = total;
    }

    public static TsReader open(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        int minLen = Format.HEAD_MAGIC.length + 4 + 4 + Format.TRAILER;
        if (data.length < minLen) {
            throw new CorruptFileException("file too short: " + data.length);
        }
        if (!matches(data, 0, Format.HEAD_MAGIC) || !matches(data, data.length - 4, Format.TAIL_MAGIC)) {
            throw new CorruptFileException("bad magic");
        }
        ByteBuffer trailer = ByteBuffer.wrap(data, data.length - Format.TRAILER, 8);
        long indexOffset = trailer.getLong();
        long indexEnd = data.length - Format.TRAILER;
        if (indexOffset < Format.HEAD_MAGIC.length || indexOffset + 8 > indexEnd) {
            throw new CorruptFileException("index offset out of range: " + indexOffset);
        }
        int start = (int) indexOffset;
        ByteBuffer idx = ByteBuffer.wrap(data, start, (int) indexEnd - start);
        long n = idx.getInt() & 0xFFFFFFFFL;
        long expectedLen = 4 + n * Format.INDEX_ENTRY + 4;
        if (expectedLen != indexEnd - indexOffset) {
            throw new CorruptFileException("index length mismatch");
        }
        CRC32 crc = new CRC32();
        crc.update(data, start, (int) expectedLen - 4);
        int storedCrc = ByteBuffer.wrap(data, (int) indexEnd - 4, 4).getInt();
        if ((int) crc.getValue() != storedCrc) {
            throw new CorruptFileException("index checksum mismatch");
        }
        List<Entry> entries = new ArrayList<>((int) n);
        long total = 0;
        long prevMax = 0;
        long minOffset = Format.HEAD_MAGIC.length;
        for (int i = 0; i < n; i++) {
            Entry e = new Entry(idx.getLong(), idx.getLong(), idx.getLong(), idx.getInt());
            if (e.offset() < minOffset || e.offset() + Format.BLOCK_HEADER > indexOffset
                    || e.count() < 1 || e.minTs() > e.maxTs() || (i > 0 && e.minTs() <= prevMax)) {
                throw new CorruptFileException("invalid index entry " + i);
            }
            minOffset = e.offset() + Format.BLOCK_HEADER;
            prevMax = e.maxTs();
            total += e.count();
            entries.add(e);
        }
        return new TsReader(data, List.copyOf(entries), total);
    }

    private static boolean matches(byte[] data, int at, byte[] magic) {
        for (int i = 0; i < magic.length; i++) {
            if (data[at + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    public long count() {
        return total;
    }

    public int blockCount() {
        return entries.size();
    }

    public OptionalLong minTs() {
        return entries.isEmpty() ? OptionalLong.empty() : OptionalLong.of(entries.get(0).minTs());
    }

    public OptionalLong maxTs() {
        return entries.isEmpty() ? OptionalLong.empty() : OptionalLong.of(entries.get(entries.size() - 1).maxTs());
    }

    /** Number of blocks decoded so far by this reader (for tests and diagnostics). */
    public int blocksDecoded() {
        return decoded.get();
    }

    public List<Point> all() throws IOException {
        return range(Long.MIN_VALUE, Long.MAX_VALUE, true);
    }

    /** Points with {@code from <= ts < toExclusive}, decoding only blocks that can overlap. */
    public List<Point> range(long from, long toExclusive) throws IOException {
        return range(from, toExclusive, false);
    }

    private List<Point> range(long from, long to, boolean inclusiveMax) throws IOException {
        List<Point> result = new ArrayList<>();
        if (!inclusiveMax && from >= to) {
            return result;
        }
        for (int i = firstBlockEndingAtOrAfter(from); i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!inclusiveMax && e.minTs() >= to) {
                break;
            }
            for (Point p : decodeBlock(e)) {
                if (p.ts() >= from && (inclusiveMax || p.ts() < to)) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    /** Binary search over blocks (maxTs ascending) for the first whose maxTs >= ts. */
    private int firstBlockEndingAtOrAfter(long ts) {
        int lo = 0;
        int hi = entries.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (entries.get(mid).maxTs() >= ts) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    private List<Point> decodeBlock(Entry e) throws IOException {
        int off = (int) e.offset();
        ByteBuffer header = ByteBuffer.wrap(data, off, Format.BLOCK_HEADER);
        long len = header.getInt() & 0xFFFFFFFFL;
        int storedCrc = header.getInt();
        long payloadStart = e.offset() + Format.BLOCK_HEADER;
        if (payloadStart + len > data.length - Format.TRAILER) {
            throw new CorruptFileException("block length out of range at " + e.offset());
        }
        CRC32 crc = new CRC32();
        crc.update(data, (int) payloadStart, (int) len);
        if ((int) crc.getValue() != storedCrc) {
            throw new CorruptFileException("block checksum mismatch at " + e.offset());
        }
        decoded.incrementAndGet();
        List<Point> pts = BlockCodec.decode(ByteBuffer.wrap(data, (int) payloadStart, (int) len), e.count());
        if (pts.get(0).ts() != e.minTs() || pts.get(pts.size() - 1).ts() != e.maxTs()) {
            throw new CorruptFileException("block disagrees with index at " + e.offset());
        }
        return pts;
    }
}
