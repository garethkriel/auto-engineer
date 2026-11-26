package ae.tar;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Test helpers that build raw tar bytes independently of {@link TarWriter}. */
final class T {
    static final int BLOCK = 512;

    private T() { }

    static void put(byte[] b, int off, String s) {
        byte[] x = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(x, 0, b, off, x.length);
    }

    /** Writes {@code value} as zero-padded octal in {@code len - 1} digits plus NUL. */
    static void putOct(byte[] b, int off, int len, long value) {
        String s = Long.toOctalString(value);
        if (s.length() > len - 1) {
            throw new IllegalArgumentException("octal too wide: " + s);
        }
        put(b, off, "0".repeat(len - 1 - s.length()) + s);
    }

    /** Recomputes the header checksum (unsigned byte sum with the field as 8 spaces) in place. */
    static byte[] seal(byte[] h) {
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        long sum = 0;
        for (byte x : h) {
            sum += x & 0xFF;
        }
        put(h, 148, String.format("%06o", sum));
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    /** A valid sealed header block for a regular entry named {@code name} with the given size. */
    static byte[] header(String name, char type, long size) {
        byte[] h = new byte[BLOCK];
        put(h, 0, name);
        putOct(h, 100, 8, 0644);
        putOct(h, 108, 8, 0);
        putOct(h, 116, 8, 0);
        putOct(h, 124, 12, size);
        putOct(h, 136, 12, 1700000000L);
        h[156] = (byte) type;
        put(h, 257, "ustar");
        put(h, 263, "00");
        return seal(h);
    }

    static byte[] zeros(int n) {
        return new byte[n];
    }

    /** Pads {@code data} with zeros up to a multiple of 512 (nothing is added when already aligned). */
    static byte[] padded(byte[] data) {
        int n = (data.length + BLOCK - 1) / BLOCK * BLOCK;
        return java.util.Arrays.copyOf(data, n);
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    static byte[] end() {
        return zeros(2 * BLOCK);
    }

    /** Header + padded body for a regular file. */
    static byte[] file(String name, String body) {
        byte[] d = body.getBytes(StandardCharsets.UTF_8);
        return cat(header(name, '0', d.length), padded(d));
    }

    /** A pax 'x' entry holding the given raw record text (already formatted). */
    static byte[] paxRaw(String records) {
        byte[] d = records.getBytes(StandardCharsets.UTF_8);
        return cat(header("PaxHeader/x", 'x', d.length), padded(d));
    }

    /** A pax-style entry of the given typeflag ('x' or 'g') holding the raw record text. */
    static byte[] paxOf(char type, String records) {
        byte[] d = records.getBytes(StandardCharsets.UTF_8);
        return cat(header("PaxHeader/x", type, d.length), padded(d));
    }

    /** A pax 'x' entry with well-formed records for key/value pairs. */
    static byte[] pax(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            sb.append(Pax.record(kv[i], kv[i + 1]));
        }
        return paxRaw(sb.toString());
    }

    static byte[] archive(byte[]... parts) {
        return cat(cat(parts), end());
    }

    static Entry f(String name, String body) {
        return Entry.file(name, 0644, 1700000000L, body.getBytes(StandardCharsets.UTF_8));
    }

    static List<String> names(List<Entry> es) {
        List<String> r = new ArrayList<>();
        for (Entry e : es) {
            r.add(e.name());
        }
        return r;
    }

    private static final String[] ALPHABET = {"a", "b", "Z", "0", "7", " ", "=", ".", "-", "_", "é", "日", "\uD83D\uDE00"};

    /** The JVM can name files with non-ASCII characters only when sun.jnu.encoding allows it (not under the POSIX locale). */
    static final boolean FS_UNICODE = canEncodeFileNames();

    private static boolean canEncodeFileNames() {
        try {
            String enc = System.getProperty("sun.jnu.encoding", "");
            return java.nio.charset.Charset.forName(enc).newEncoder().canEncode("é日\uD83D\uDE00");
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String randomSegment(java.util.Random r, int maxLen) {
        return randomSegment(r, maxLen, true);
    }

    static String randomSegment(java.util.Random r, int maxLen, boolean unicode) {
        StringBuilder sb = new StringBuilder();
        int n = 1 + r.nextInt(maxLen);
        int range = unicode ? ALPHABET.length : 10; // the first ten symbols are ASCII
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET[r.nextInt(range)]);
        }
        String s = sb.toString();
        return (s.equals(".") || s.equals("..")) ? "x" : s;
    }

    static String randomName(java.util.Random r) {
        int depth = 1 + r.nextInt(5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(randomSegment(r, r.nextInt(8) == 0 ? 60 : 12));
        }
        return sb.toString();
    }

    static List<Entry> randomEntries(java.util.Random r, int max) {
        return randomEntries(r, max, false);
    }

    /**
     * Random entries (no newline in any text, so GNU tar listings can be compared line by line).
     * {@code gnuSafe} keeps ids within 32 bits and times within 33 bits, which is all GNU tar accepts.
     */
    static List<Entry> randomEntries(java.util.Random r, int max, boolean gnuSafe) {
        long[] ids = gnuSafe ? new long[] {0, 1000, 2097151, 2097152, 4294967295L}
            : new long[] {0, 1000, 2097151, 2097152, Long.MAX_VALUE};
        long[] times = gnuSafe ? new long[] {0, 1700000000L, 8589934591L, 8589934592L, -1, 123456789}
            : new long[] {0, 1700000000L, 8589934591L, 8589934592L, -1, Long.MIN_VALUE, Long.MAX_VALUE, 123456789};
        String[] users = {"", "root", "u".repeat(31), "u".repeat(32), "n".repeat(40), "ünï"};
        List<Entry> out = new ArrayList<>();
        int n = 1 + r.nextInt(max);
        for (int i = 0; i < n; i++) {
            String name = randomName(r);
            int kind = r.nextInt(4);
            Entry.Type type = Entry.Type.values()[kind];
            String link = "";
            byte[] data = new byte[0];
            if (type == Entry.Type.FILE) {
                data = new byte[r.nextInt(3) == 0 ? 0 : r.nextInt(1500)];
                r.nextBytes(data);
            } else if (type == Entry.Type.SYMLINK) {
                link = r.nextBoolean() ? randomName(r) : "../" + randomSegment(r, 120);
            } else if (type == Entry.Type.HARDLINK) {
                link = randomName(r);
            }
            out.add(new Entry(name, type, r.nextInt(010000), ids[r.nextInt(ids.length)], ids[r.nextInt(ids.length)],
                times[r.nextInt(times.length)], link, users[r.nextInt(users.length)], users[r.nextInt(users.length)], data));
        }
        return out;
    }

    /** Number of pax 'x' entries in an archive, by walking the headers. */
    static int paxCount(byte[] tar) {
        int pos = 0;
        int count = 0;
        while (pos + BLOCK <= tar.length) {
            boolean zero = true;
            for (int i = 0; i < BLOCK && zero; i++) {
                zero = tar[pos + i] == 0;
            }
            if (zero) {
                break;
            }
            if (tar[pos + 156] == 'x') {
                count++;
            }
            long size = Long.parseLong(new String(tar, pos + 124, 11, StandardCharsets.US_ASCII), 8);
            pos += BLOCK + (int) ((size + BLOCK - 1) / BLOCK * BLOCK);
        }
        return count;
    }
}
