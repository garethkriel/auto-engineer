package ae.tar;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic POSIX ustar writer. Fields that do not fit a ustar header move to a pax extended header
 * ({@code x} entry) that precedes the entry.
 */
public final class TarWriter {
    static final int BLOCK = 512;
    private static final int NAME_MAX = 100;
    private static final int PREFIX_MAX = 155;
    private static final int USER_MAX = 31;
    private static final long ID_MAX = 07777777L;
    private static final long TIME_MAX = 077777777777L;

    private TarWriter() { }

    /** Serialises the entries followed by two zero blocks (no blocking-factor padding). */
    public static byte[] write(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Entry e : entries) {
            writeEntry(out, e);
        }
        out.writeBytes(new byte[2 * BLOCK]);
        return out.toByteArray();
    }

    private static void writeEntry(ByteArrayOutputStream out, Entry e) {
        boolean dir = e.type() == Entry.Type.DIRECTORY;
        String stored = dir ? e.name() + "/" : e.name();
        byte[] storedBytes = Text.utf8(stored, "name");
        Map<String, String> pax = new LinkedHashMap<>();

        String name;
        String prefix = "";
        if (Text.isAscii(storedBytes) && storedBytes.length <= NAME_MAX) {
            name = stored;
        } else {
            int split = Text.isAscii(storedBytes) ? splitPoint(storedBytes) : -1;
            if (split >= 0) {
                prefix = stored.substring(0, split);
                name = stored.substring(split + 1);
            } else {
                pax.put("path", stored);
                name = placeholder(stored, NAME_MAX);
            }
        }

        String link = e.linkName();
        if (!Text.isAscii(Text.utf8(link, "linkName")) || link.length() > NAME_MAX) {
            pax.put("linkpath", link);
            link = placeholder(link, NAME_MAX);
        }

        long uid = e.uid();
        long gid = e.gid();
        if (uid > ID_MAX) {
            pax.put("uid", Long.toString(uid));
            uid = 0;
        }
        if (gid > ID_MAX) {
            pax.put("gid", Long.toString(gid));
            gid = 0;
        }

        String uname = e.uname();
        String gname = e.gname();
        if (!fitsUser(uname)) {
            pax.put("uname", uname);
            uname = placeholder(uname, USER_MAX);
        }
        if (!fitsUser(gname)) {
            pax.put("gname", gname);
            gname = placeholder(gname, USER_MAX);
        }

        long mtime = e.mtime();
        if (mtime < 0 || mtime > TIME_MAX) {
            pax.put("mtime", Long.toString(mtime));
            mtime = 0;
        }

        if (!pax.isEmpty()) {
            byte[] records = Pax.encode(pax);
            String last = e.name().substring(e.name().lastIndexOf('/') + 1);
            String paxName = "PaxHeader/" + placeholder(last, 60);
            out.writeBytes(header(paxName, "", 0644, 0, 0, records.length, mtime, 'x', "", "", ""));
            out.writeBytes(padded(records));
        }
        out.writeBytes(header(name, prefix, e.mode(), uid, gid, e.data().length, mtime, e.type().flag, link, uname, gname));
        out.writeBytes(padded(e.data()));
    }

    private static boolean fitsUser(String s) {
        byte[] b = Text.utf8(s, "user name");
        return Text.isAscii(b) && b.length <= USER_MAX;
    }

    /** Index of the rightmost '/' giving prefix <= 155 and a non-empty name <= 100, or -1. */
    private static int splitPoint(byte[] b) {
        for (int i = Math.min(PREFIX_MAX, b.length - 2); i >= 1; i--) {
            if (b[i] == '/' && b.length - i - 1 <= NAME_MAX) {
                return i;
            }
        }
        return -1;
    }

    /** ASCII text of at most {@code max} bytes standing in for a value that lives in the pax header. */
    static String placeholder(String s, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x80 && c >= 0x20 ? c : '_');
        }
        return sb.toString();
    }

    private static byte[] padded(byte[] data) {
        return java.util.Arrays.copyOf(data, (data.length + BLOCK - 1) / BLOCK * BLOCK);
    }

    private static byte[] header(String name, String prefix, int mode, long uid, long gid, long size, long mtime,
                                 char type, String link, String uname, String gname) {
        byte[] h = new byte[BLOCK];
        putText(h, 0, NAME_MAX, name);
        putOctal(h, 100, 8, mode);
        putOctal(h, 108, 8, uid);
        putOctal(h, 116, 8, gid);
        putOctal(h, 124, 12, size);
        putOctal(h, 136, 12, mtime);
        h[156] = (byte) type;
        putText(h, 157, NAME_MAX, link);
        putText(h, 257, 6, "ustar"); // "ustar" + NUL
        putText(h, 263, 2, "00");
        putText(h, 265, 32, uname);
        putText(h, 297, 32, gname);
        putText(h, 345, PREFIX_MAX, prefix);
        // checksum: unsigned byte sum with the field read as eight spaces
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xFF;
        }
        putOctal(h, 148, 7, sum); // six digits + NUL, then the space already in place
        h[155] = ' ';
        return h;
    }

    private static void putText(byte[] h, int off, int len, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > len) {
            throw new IllegalStateException("field overflow: " + s);
        }
        System.arraycopy(b, 0, h, off, b.length);
    }

    /** Zero-padded octal in {@code len - 1} digits followed by a NUL. */
    private static void putOctal(byte[] h, int off, int len, long value) {
        String s = Long.toOctalString(value);
        if (s.length() > len - 1) {
            throw new IllegalStateException("octal overflow: " + value);
        }
        String padded = "0".repeat(len - 1 - s.length()) + s;
        System.arraycopy(padded.getBytes(StandardCharsets.US_ASCII), 0, h, off, len - 1);
        h[off + len - 1] = 0;
    }
}
