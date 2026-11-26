package ae.arch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Strict POSIX ustar reader (regular files and directories). */
public final class TarReader {
    private static final int B = TarWriter.BLOCK;

    private TarReader() {}

    public static List<ArchiveEntry> read(byte[] tar) {
        return read(tar, false);
    }

    /**
     * @param stripDotSlash accept the names produced by {@code tar -c .}: drop the root entry {@code ./}
     *                      and a leading {@code ./} from every other name
     */
    public static List<ArchiveEntry> read(byte[] tar, boolean stripDotSlash) {
        if (tar.length % B != 0) {
            throw new ArchiveException("tar length " + tar.length + " is not a whole number of 512-byte blocks");
        }
        List<ArchiveEntry> out = new ArrayList<>();
        int p = 0;
        while (true) {
            if (p >= tar.length) {
                throw new ArchiveException("missing end-of-archive marker (two zero blocks)");
            }
            if (isZero(tar, p, B)) {
                if (tar.length - p < 2 * B) {
                    throw new ArchiveException("missing end-of-archive marker (two zero blocks)");
                }
                if (!isZero(tar, p, tar.length - p)) {
                    throw new ArchiveException("non-zero data after the end-of-archive marker");
                }
                break;
            }
            p = readEntry(tar, p, out, stripDotSlash);
        }
        Archives.checkTree(out);
        return out;
    }

    private static boolean isZero(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static int readEntry(byte[] t, int p, List<ArchiveEntry> out, boolean stripDotSlash) {
        byte[] h = Arrays.copyOfRange(t, p, p + B);
        long stored = octal(h, TarWriter.CHKSUM_OFF, 8, "checksum");
        if (stored != TarWriter.checksum(h)) {
            throw new ArchiveException("checksum mismatch in header at " + p);
        }
        if (!Arrays.equals(h, TarWriter.MAGIC_OFF, TarWriter.MAGIC_OFF + 8, ("ustar\0" + "00").getBytes(StandardCharsets.ISO_8859_1), 0, 8)) {
            throw new ArchiveException("bad magic or version (only POSIX ustar is supported) at " + p);
        }
        int type = h[TarWriter.TYPE_OFF];
        boolean dir;
        if (type == '0' || type == 0) {
            dir = false;
        } else if (type == '5') {
            dir = true;
        } else {
            throw new ArchiveException("unsupported typeflag 0x" + Integer.toHexString(type & 0xFF) + " at " + p);
        }
        octal(h, TarWriter.MODE_OFF, 8, "mode");
        octal(h, TarWriter.UID_OFF, 8, "uid");
        octal(h, TarWriter.GID_OFF, 8, "gid");
        octal(h, TarWriter.MTIME_OFF, 12, "mtime");
        long size = octal(h, TarWriter.SIZE_OFF, 12, "size");
        if (!isZero(h, TarWriter.LINK_OFF, 100)) {
            throw new ArchiveException("linkname must be empty for files and directories");
        }
        String name = text(h, TarWriter.NAME_OFF, TarWriter.NAME_LEN, "name");
        String prefix = text(h, TarWriter.PREFIX_OFF, TarWriter.PREFIX_LEN, "prefix");
        String full = prefix.isEmpty() ? name : prefix + "/" + name;
        if (dir) {
            if (size != 0) {
                throw new ArchiveException("directory " + full + " has data");
            }
            if (!full.endsWith("/")) {
                full += "/";
            }
        } else if (full.endsWith("/")) {
            throw new ArchiveException("file name ends with '/': " + full);
        }
        int dataStart = p + B;
        long blocks = (size + B - 1) / B;
        if (size > t.length - dataStart) { // length and dataStart are multiples of 512, so the padding fits too
            throw new ArchiveException("truncated data for " + full + ": declares " + size + " bytes");
        }
        int end = dataStart + (int) size;
        int next = dataStart + (int) (blocks * B);
        if (!isZero(t, end, next - end)) {
            throw new ArchiveException("non-zero padding after the data of " + full);
        }
        if (stripDotSlash) {
            if (full.equals("./")) {
                return next;
            }
            if (full.startsWith("./")) {
                full = full.substring(2);
            }
        }
        try {
            out.add(new ArchiveEntry(full, Arrays.copyOfRange(t, dataStart, end)));
        } catch (IllegalArgumentException ex) {
            throw new ArchiveException("invalid name: " + ex.getMessage(), ex);
        }
        return next;
    }

    /** NUL-terminated field; everything after the first NUL must be NUL. */
    private static String text(byte[] h, int off, int len, String what) {
        int n = 0;
        while (n < len && h[off + n] != 0) {
            n++;
        }
        if (!isZero(h, off + n, len - n)) {
            throw new ArchiveException(what + " field has data after its NUL terminator");
        }
        return Names.decodeUtf8(h, off, n);
    }

    /** Octal digits (at least one) followed only by NULs/spaces (at least one terminator byte). */
    private static long octal(byte[] h, int off, int len, String what) {
        int i = off;
        long v = 0;
        while (i < off + len && h[i] >= '0' && h[i] <= '7') {
            v = v * 8 + (h[i] - '0');
            i++;
        }
        if (i == off || i == off + len) {
            throw new ArchiveException("bad octal field: " + what);
        }
        for (int j = i; j < off + len; j++) {
            if (h[j] != 0 && h[j] != ' ') {
                throw new ArchiveException("bad octal field: " + what);
            }
        }
        return v;
    }
}
