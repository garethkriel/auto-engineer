package ae.arch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** POSIX ustar writer: regular files and directories only, fixed owner/mtime, deterministic output. */
public final class TarWriter {
    static final int BLOCK = 512;
    static final int NAME_OFF = 0;
    static final int NAME_LEN = 100;
    static final int MODE_OFF = 100;
    static final int UID_OFF = 108;
    static final int GID_OFF = 116;
    static final int SIZE_OFF = 124;
    static final int MTIME_OFF = 136;
    static final int CHKSUM_OFF = 148;
    static final int TYPE_OFF = 156;
    static final int LINK_OFF = 157;
    static final int MAGIC_OFF = 257;
    static final int VERSION_OFF = 263;
    static final int UNAME_OFF = 265;
    static final int GNAME_OFF = 297;
    static final int DEVMAJOR_OFF = 329;
    static final int DEVMINOR_OFF = 337;
    static final int PREFIX_OFF = 345;
    static final int PREFIX_LEN = 155;

    private TarWriter() {}

    public static byte[] write(List<ArchiveEntry> entries) {
        Objects.requireNonNull(entries);
        Archives.checkTree(entries);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ArchiveEntry e : entries) {
            byte[] data = e.data();
            out.writeBytes(header(e.name(), data.length, e.directory()));
            out.writeBytes(data);
            out.writeBytes(new byte[(BLOCK - data.length % BLOCK) % BLOCK]);
        }
        out.writeBytes(new byte[2 * BLOCK]);
        return out.toByteArray();
    }

    private static byte[] header(String name, int size, boolean dir) {
        byte[] n = Names.utf8(name);
        byte[] prefix = new byte[0];
        if (n.length > NAME_LEN) {
            int split = -1;
            // last '/' that leaves a prefix of <= 155 bytes and a non-empty rest of <= 100 bytes
            for (int i = Math.min(PREFIX_LEN, n.length - 2); i >= 1; i--) {
                if (n[i] == '/' && n.length - i - 1 <= NAME_LEN) {
                    split = i;
                    break;
                }
            }
            if (split < 0) {
                throw new IllegalArgumentException("name cannot be split into ustar prefix/name: " + name);
            }
            prefix = Arrays.copyOfRange(n, 0, split);
            n = Arrays.copyOfRange(n, split + 1, n.length);
        }
        byte[] h = new byte[BLOCK];
        System.arraycopy(n, 0, h, NAME_OFF, n.length);
        System.arraycopy(prefix, 0, h, PREFIX_OFF, prefix.length);
        octal(h, MODE_OFF, 8, dir ? 0755 : 0644);
        octal(h, UID_OFF, 8, 0);
        octal(h, GID_OFF, 8, 0);
        octal(h, SIZE_OFF, 12, size);
        octal(h, MTIME_OFF, 12, 0);
        h[TYPE_OFF] = (byte) (dir ? '5' : '0');
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, MAGIC_OFF, 6);
        h[VERSION_OFF] = '0';
        h[VERSION_OFF + 1] = '0';
        octal(h, DEVMAJOR_OFF, 8, 0);
        octal(h, DEVMINOR_OFF, 8, 0);
        h[CHKSUM_OFF] = ' ';
        Arrays.fill(h, CHKSUM_OFF, CHKSUM_OFF + 8, (byte) ' ');
        byte[] sum = String.format("%06o", checksum(h)).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(sum, 0, h, CHKSUM_OFF, 6);
        h[CHKSUM_OFF + 6] = 0;
        h[CHKSUM_OFF + 7] = ' ';
        return h;
    }

    /** Sum of all header bytes, taking the checksum field as eight spaces. */
    static int checksum(byte[] h) {
        int sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (i >= CHKSUM_OFF && i < CHKSUM_OFF + 8) ? ' ' : (h[i] & 0xFF);
        }
        return sum;
    }

    /** {@code len - 1} zero-padded octal digits followed by a NUL. */
    private static void octal(byte[] h, int off, int len, long v) {
        String s = Long.toOctalString(v);
        if (s.length() > len - 1) {
            throw new IllegalArgumentException("value too large for a " + len + "-byte octal field: " + v);
        }
        byte[] d = ("0".repeat(len - 1 - s.length()) + s).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(d, 0, h, off, len - 1);
        h[off + len - 1] = 0;
    }
}
