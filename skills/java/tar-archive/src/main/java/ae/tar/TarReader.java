package ae.tar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strict reader for POSIX ustar/pax archives held in memory. Anything surprising is a
 * {@link TarFormatException}; nothing is repaired or skipped.
 */
public final class TarReader {
    private static final int BLOCK = TarWriter.BLOCK;
    /** Largest accepted pax header body. */
    static final int MAX_PAX = 1 << 20;
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private TarReader() { }

    /** Parses a whole archive. */
    public static List<Entry> read(byte[] a) throws TarFormatException {
        if (a.length % BLOCK != 0) {
            throw new TarFormatException("archive length " + a.length + " is not a multiple of 512");
        }
        List<Entry> out = new ArrayList<>();
        Map<String, String> pending = null;
        int pos = 0;
        while (true) {
            if (pos + BLOCK > a.length) {
                throw new TarFormatException("missing end-of-archive marker");
            }
            if (isZero(a, pos, BLOCK)) {
                if (pos + 2 * BLOCK > a.length || !isZero(a, pos + BLOCK, BLOCK)) {
                    throw new TarFormatException("incomplete end-of-archive marker (two zero blocks required)");
                }
                if (pending != null) {
                    throw new TarFormatException("pax header is not followed by an entry");
                }
                if (!isZero(a, pos + 2 * BLOCK, a.length - pos - 2 * BLOCK)) {
                    throw new TarFormatException("data after the end-of-archive marker");
                }
                return out;
            }
            Header h = parseHeader(a, pos);
            int bodyStart = pos + BLOCK;
            if (h.type == 'x' || h.type == 'g') {
                if (h.size > MAX_PAX) {
                    throw new TarFormatException("pax header of " + h.size + " bytes exceeds the " + MAX_PAX + " byte cap");
                }
                Map<String, String> records = Pax.parse(body(a, bodyStart, h.size));
                pos = bodyStart + (int) padded(h.size); // body() proved it fits
                if (h.type == 'x') {
                    if (pending != null) {
                        throw new TarFormatException("pax header follows another pax header");
                    }
                    pending = records;
                }
                continue;
            }
            Entry e = build(a, h, bodyStart, pending);
            pending = null;
            out.add(e);
            pos = bodyStart + (int) padded(e.type() == Entry.Type.FILE ? e.data().length : 0);
        }
    }

    // ---- header fields ----------------------------------------------------------------------------------------------

    private record Header(String name, String prefix, String link, String uname, String gname, int mode, long uid, long gid,
                          long size, long mtime, int type) { }

    private static Header parseHeader(byte[] a, int p) throws TarFormatException {
        long stored = octal(a, p + 148, 8, "checksum");
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (a[p + i] & 0xFF);
        }
        if (sum != stored) {
            throw new TarFormatException("checksum mismatch: stored " + stored + ", computed " + sum);
        }
        if (!Arrays.equals(a, p + 257, p + 263, new byte[] {'u', 's', 't', 'a', 'r', 0}, 0, 6)) {
            throw new TarFormatException("bad magic (only POSIX ustar is supported)");
        }
        if (a[p + 263] != '0' || a[p + 264] != '0') {
            throw new TarFormatException("bad version (expected 00)");
        }
        int type = a[p + 156] == 0 ? '0' : a[p + 156] & 0xFF;
        if (type != '0' && type != '1' && type != '2' && type != '5' && type != 'x' && type != 'g') {
            throw new TarFormatException("unsupported typeflag " + (type >= 0x20 && type < 0x7F ? "'" + (char) type + "'" : "0x" + Integer.toHexString(type)));
        }
        String name = text(a, p, 100, "name");
        String link = text(a, p + 157, 100, "linkname");
        String uname = text(a, p + 265, 32, "uname");
        String gname = text(a, p + 297, 32, "gname");
        String prefix = text(a, p + 345, 155, "prefix");
        long mode = octal(a, p + 100, 8, "mode");
        long uid = octal(a, p + 108, 8, "uid");
        long gid = octal(a, p + 116, 8, "gid");
        long size = octal(a, p + 124, 12, "size");
        long mtime = octal(a, p + 136, 12, "mtime");
        if (mode > 07777) {
            throw new TarFormatException("mode " + Long.toOctalString(mode) + " exceeds 07777");
        }
        device(a, p + 329);
        device(a, p + 337);
        if (!isZero(a, p + 500, 12)) {
            throw new TarFormatException("non-zero header padding");
        }
        return new Header(name, prefix, link, uname, gname, (int) mode, uid, gid, size, mtime, type);
    }

    private static void device(byte[] a, int off) throws TarFormatException {
        if (!isZero(a, off, 8) && octal(a, off, 8, "device") != 0) {
            throw new TarFormatException("device numbers must be zero");
        }
    }

    /** Digits 0-7 (at least one) followed only by NUL or space bytes (at least one). */
    private static long octal(byte[] a, int off, int len, String field) throws TarFormatException {
        int i = 0;
        long v = 0;
        while (i < len && a[off + i] >= '0' && a[off + i] <= '7') {
            v = v * 8 + (a[off + i] - '0');
            i++;
        }
        if (i == 0 || i == len) {
            throw new TarFormatException("invalid octal field " + field);
        }
        for (int k = i; k < len; k++) {
            if (a[off + k] != 0 && a[off + k] != ' ') {
                throw new TarFormatException("invalid octal field " + field);
            }
        }
        return v;
    }

    /** NUL-terminated (or full-width) UTF-8 text; bytes after the first NUL must all be NUL. */
    private static String text(byte[] a, int off, int len, String field) throws TarFormatException {
        int end = 0;
        while (end < len && a[off + end] != 0) {
            end++;
        }
        if (!isZero(a, off + end, len - end)) {
            throw new TarFormatException("field " + field + " has data after its NUL");
        }
        String s = Text.decode(a, off, end);
        if (s == null) {
            throw new TarFormatException("field " + field + " is not valid UTF-8");
        }
        return s;
    }

    // ---- entries ----------------------------------------------------------------------------------------------------

    private static Entry build(byte[] a, Header h, int bodyStart, Map<String, String> px) throws TarFormatException {
        Map<String, String> pax = px == null ? Map.of() : px;
        String name = pax.containsKey("path") ? pax.get("path") : h.prefix.isEmpty() ? h.name : h.prefix + "/" + h.name;
        String link = pax.containsKey("linkpath") ? pax.get("linkpath") : h.link;
        String uname = pax.getOrDefault("uname", h.uname);
        String gname = pax.getOrDefault("gname", h.gname);
        long uid = pax.containsKey("uid") ? paxLong(pax.get("uid"), "uid") : h.uid;
        long gid = pax.containsKey("gid") ? paxLong(pax.get("gid"), "gid") : h.gid;
        long size = pax.containsKey("size") ? paxLong(pax.get("size"), "size") : h.size;
        long mtime = pax.containsKey("mtime") ? paxTime(pax.get("mtime")) : h.mtime;

        Entry.Type type = switch (h.type) {
            case '1' -> Entry.Type.HARDLINK;
            case '2' -> Entry.Type.SYMLINK;
            case '5' -> Entry.Type.DIRECTORY;
            default -> Entry.Type.FILE;
        };
        if (type != Entry.Type.FILE && size != 0) {
            throw new TarFormatException(type + " entry must have size 0 but has size " + size);
        }
        if (type == Entry.Type.DIRECTORY && name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        byte[] data = body(a, bodyStart, size);
        try {
            return new Entry(name, type, h.mode, uid, gid, mtime, link, uname, gname, data);
        } catch (IllegalArgumentException ex) {
            throw new TarFormatException("invalid entry: " + ex.getMessage());
        }
    }

    /** The {@code size} data bytes at {@code start}, checking bounds before allocating and the zero padding after. */
    private static byte[] body(byte[] a, int start, long size) throws TarFormatException {
        long avail = a.length - (long) start;
        if (size > avail || padded(size) > avail) {
            throw new TarFormatException("truncated: entry needs " + size + " data bytes but only " + avail + " bytes remain");
        }
        int n = (int) size;
        int paddedEnd = start + (int) padded(size);
        if (!isZero(a, start + n, paddedEnd - start - n)) {
            throw new TarFormatException("non-zero padding after entry data");
        }
        return Arrays.copyOfRange(a, start, start + n);
    }

    private static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    private static long paxLong(String v, String key) throws TarFormatException {
        if (!DIGITS.matcher(v).matches()) {
            throw new TarFormatException("invalid pax " + key + " value '" + v + "'");
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new TarFormatException("pax " + key + " value out of range: " + v);
        }
    }

    /** Decimal seconds with an optional fraction, rounded toward negative infinity. */
    private static long paxTime(String v) throws TarFormatException {
        if (!DECIMAL.matcher(v).matches() || v.length() > 40) {
            throw new TarFormatException("invalid pax mtime value '" + v + "'");
        }
        try {
            return new BigDecimal(v).setScale(0, RoundingMode.FLOOR).longValueExact();
        } catch (ArithmeticException e) {
            throw new TarFormatException("pax mtime value out of range: " + v);
        }
    }

    private static boolean isZero(byte[] a, int off, int len) {
        for (int i = 0; i < len; i++) {
            if (a[off + i] != 0) {
                return false;
            }
        }
        return true;
    }
}
