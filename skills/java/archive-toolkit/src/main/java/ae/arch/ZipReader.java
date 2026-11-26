package ae.arch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Strict ZIP reader for classic archives. Every length and offset is validated before it is used,
 * and anything this reader does not model (ZIP64, encryption, other methods) is rejected rather than guessed.
 */
public final class ZipReader {
    /** Default cap on the declared uncompressed size of one entry. */
    public static final long DEFAULT_MAX_ENTRY_SIZE = 64L << 20;
    private static final int ALLOWED_FLAGS = 0x0002 | 0x0004 | 0x0008 | ZipWriter.FLAG_UTF8; // bits 1-2: deflate options
    private static final int FLAG_ENCRYPTED = 0x0001;
    private static final int FLAG_DESCRIPTOR = 0x0008;

    private ZipReader() {}

    private record Eocd(int pos, int entries, long cdSize, long cdOffset, int commentLen) {}

    private record Central(String name, int flags, int method, long crc, long csize, long size, long localOffset) {}

    private static int u16(byte[] b, long pos) {
        need(b, pos, 2);
        return (b[(int) pos] & 0xFF) | (b[(int) pos + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, long pos) {
        need(b, pos, 4);
        return (u16(b, pos) | (long) u16(b, pos + 2) << 16) & 0xFFFFFFFFL;
    }

    private static void need(byte[] b, long pos, long n) {
        if (pos < 0 || n < 0 || pos + n > b.length) {
            throw new ArchiveException("truncated archive: need " + n + " bytes at " + pos + " of " + b.length);
        }
    }

    private static Eocd findEocd(byte[] z) {
        int lowest = Math.max(0, z.length - ZipWriter.EOCD_SIZE - 0xFFFF);
        for (int i = z.length - ZipWriter.EOCD_SIZE; i >= lowest; i--) {
            if (u32(z, i) == (ZipWriter.EOCD_SIG & 0xFFFFFFFFL) && i + ZipWriter.EOCD_SIZE + u16(z, i + 20) == z.length) {
                return new Eocd(i, u16(z, i + 10), u32(z, i + 12), u32(z, i + 16), u16(z, i + 20));
            }
        }
        throw new ArchiveException("end of central directory not found (comment must end exactly at end of file)");
    }

    /** The archive comment. */
    public static String comment(byte[] zip) {
        Eocd e = findEocd(zip);
        return new String(zip, e.pos + ZipWriter.EOCD_SIZE, e.commentLen, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static List<ArchiveEntry> read(byte[] zip) {
        return read(zip, DEFAULT_MAX_ENTRY_SIZE);
    }

    public static List<ArchiveEntry> read(byte[] zip, long maxEntrySize) {
        Eocd e = findEocd(zip);
        if (u16(zip, e.pos + 4) != 0 || u16(zip, e.pos + 6) != 0) {
            throw new ArchiveException("multi-disk archives are not supported (disk number)");
        }
        if (u16(zip, e.pos + 8) != e.entries) {
            throw new ArchiveException("entries on this disk differ from total entries");
        }
        if (e.entries == 0xFFFF || e.cdSize == 0xFFFFFFFFL || e.cdOffset == 0xFFFFFFFFL) {
            throw new ArchiveException("zip64 archives are not supported");
        }
        if (e.cdOffset + e.cdSize != e.pos) {
            throw new ArchiveException("central directory must end exactly at the end-of-central-directory record");
        }
        List<Central> cen = readCentral(zip, e);
        checkRanges(zip, cen, (int) e.cdOffset);
        List<ArchiveEntry> out = new ArrayList<>();
        for (Central c : cen) {
            out.add(extract(zip, c, maxEntrySize, (int) e.cdOffset));
        }
        Archives.checkTree(out);
        return out;
    }

    private static List<Central> readCentral(byte[] z, Eocd e) {
        List<Central> out = new ArrayList<>();
        long p = e.cdOffset;
        long end = e.cdOffset + e.cdSize;
        for (int k = 0; k < e.entries; k++) {
            if (p + ZipWriter.CENTRAL_SIZE > end || u32(z, p) != (ZipWriter.CENTRAL_SIG & 0xFFFFFFFFL)) {
                throw new ArchiveException("bad central directory header #" + k);
            }
            int versionNeeded = u16(z, p + 6);
            int flags = u16(z, p + 8);
            int method = u16(z, p + 10);
            long crc = u32(z, p + 16);
            long csize = u32(z, p + 20);
            long size = u32(z, p + 24);
            int nlen = u16(z, p + 28);
            int elen = u16(z, p + 30);
            int clen = u16(z, p + 32);
            int disk = u16(z, p + 34);
            long off = u32(z, p + 42);
            long next = p + ZipWriter.CENTRAL_SIZE + nlen + elen + clen;
            if (next > end) {
                throw new ArchiveException("central directory header #" + k + " overruns the directory");
            }
            if ((flags & FLAG_ENCRYPTED) != 0) {
                throw new ArchiveException("encrypted entries are not supported");
            }
            if ((flags & ~ALLOWED_FLAGS) != 0) {
                throw new ArchiveException("unsupported general purpose flag bits 0x" + Integer.toHexString(flags));
            }
            if (versionNeeded > 20) {
                throw new ArchiveException("unsupported version needed: " + versionNeeded);
            }
            if (disk != 0) {
                throw new ArchiveException("multi-disk archives are not supported (entry disk)");
            }
            if (method != 0 && method != 8) {
                throw new ArchiveException("unsupported compression method " + method);
            }
            if (csize == 0xFFFFFFFFL || size == 0xFFFFFFFFL || off == 0xFFFFFFFFL) {
                throw new ArchiveException("zip64 archives are not supported (entry fields)");
            }
            if (method == 0 && csize != size) {
                throw new ArchiveException("stored entry with different compressed and uncompressed size");
            }
            String name = Names.decodeUtf8(z, (int) p + ZipWriter.CENTRAL_SIZE, nlen);
            out.add(new Central(name, flags, method, crc, csize, size, off));
            p = next;
        }
        if (p != end) {
            throw new ArchiveException("unused bytes at the end of the central directory");
        }
        return out;
    }

    /** Local header + data ranges must lie before the central directory and must not overlap. */
    private static void checkRanges(byte[] z, List<Central> cen, int cdOffset) {
        List<long[]> ranges = new ArrayList<>();
        for (Central c : cen) {
            long[] r = dataRange(z, c, cdOffset);
            ranges.add(new long[] {c.localOffset, r[1]});
        }
        ranges.sort(Comparator.comparingLong(r -> r[0]));
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i)[0] < ranges.get(i - 1)[1]) {
                throw new ArchiveException("overlapping entries");
            }
        }
    }

    /** Returns {dataStart, dataEnd} after checking the local header against the central one. */
    private static long[] dataRange(byte[] z, Central c, int cdOffset) {
        long o = c.localOffset;
        if (o + ZipWriter.LOCAL_SIZE > cdOffset || u32(z, o) != (ZipWriter.LOCAL_SIG & 0xFFFFFFFFL)) {
            throw new ArchiveException("bad local header for " + c.name);
        }
        int lnlen = u16(z, o + 26);
        int lelen = u16(z, o + 28);
        if (u16(z, o + 8) != c.method) {
            throw new ArchiveException("local header method differs from central for " + c.name);
        }
        if (lnlen != Names.utf8(c.name).length
            || !Arrays.equals(z, (int) o + ZipWriter.LOCAL_SIZE, (int) o + ZipWriter.LOCAL_SIZE + lnlen,
                Names.utf8(c.name), 0, lnlen)) {
            throw new ArchiveException("local header name differs from central for " + c.name);
        }
        if ((c.flags & FLAG_DESCRIPTOR) == 0
            && (u32(z, o + 14) != c.crc || u32(z, o + 18) != c.csize || u32(z, o + 22) != c.size)) {
            throw new ArchiveException("local header crc/sizes differ from central for " + c.name);
        }
        long start = o + ZipWriter.LOCAL_SIZE + lnlen + lelen;
        long end = start + c.csize;
        if (end > cdOffset) {
            throw new ArchiveException("entry data runs into the central directory: " + c.name);
        }
        return new long[] {start, end};
    }

    private static ArchiveEntry extract(byte[] z, Central c, long maxEntrySize, int cdOffset) {
        if (c.size > maxEntrySize) {
            throw new ArchiveException("entry too large: " + c.name + " declares " + c.size + " bytes (limit " + maxEntrySize + ")");
        }
        long[] r = dataRange(z, c, cdOffset);
        int start = (int) r[0];
        byte[] data;
        if (c.method == 0) {
            data = Arrays.copyOfRange(z, start, (int) r[1]);
        } else {
            data = inflate(z, start, (int) c.csize, (int) c.size, c.name);
        }
        if (Crc32.of(data) != c.crc) {
            throw new ArchiveException("crc mismatch for " + c.name);
        }
        try {
            return new ArchiveEntry(c.name, data);
        } catch (IllegalArgumentException ex) {
            throw new ArchiveException("invalid entry: " + ex.getMessage(), ex);
        }
    }

    private static byte[] inflate(byte[] z, int start, int csize, int size, String name) {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(z, start, csize);
            byte[] out = new byte[size];
            int n = 0;
            while (n < size) {
                int got = inf.inflate(out, n, size - n);
                if (got == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                n += got;
            }
            if (n != size) {
                throw new ArchiveException("size mismatch for " + name + ": stream ends after " + n + " of " + size + " bytes");
            }
            if (inf.inflate(new byte[1]) != 0) {
                throw new ArchiveException("size mismatch for " + name + ": stream is longer than the declared size");
            }
            if (!inf.finished()) {
                throw new ArchiveException("size mismatch for " + name + ": deflate stream not finished");
            }
            if (inf.getRemaining() != 0) {
                throw new ArchiveException("compressed data of " + name + " has " + inf.getRemaining() + " trailing bytes");
            }
            return out;
        } catch (DataFormatException ex) {
            throw new ArchiveException("corrupt deflate stream in " + name + ": " + ex.getMessage(), ex);
        } finally {
            inf.end();
        }
    }
}
