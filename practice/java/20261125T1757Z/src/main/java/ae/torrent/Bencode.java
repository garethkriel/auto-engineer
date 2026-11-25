package ae.torrent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Canonical bencode encoder and strict decoder. */
public final class Bencode {
    /** Maximum nesting depth accepted by the decoder (top-level value = depth 1). */
    public static final int MAX_DEPTH = 64;

    private Bencode() {
    }

    public static byte[] encode(BValue v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(v, out);
        return out.toByteArray();
    }

    private static void ascii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    private static void write(BValue v, ByteArrayOutputStream out) {
        switch (v) {
            case BInt i -> {
                ascii(out, "i" + i.value() + "e");
            }
            case BStr s -> {
                ascii(out, s.length() + ":");
                byte[] b = s.bytes();
                out.write(b, 0, b.length);
            }
            case BList l -> {
                out.write('l');
                for (BValue x : l.items()) {
                    write(x, out);
                }
                out.write('e');
            }
            case BDict d -> {
                out.write('d');
                for (Map.Entry<BStr, BValue> e : d.entries()) {
                    write(e.getKey(), out);
                    write(e.getValue(), out);
                }
                out.write('e');
            }
        }
    }

    /** Decodes exactly one value; anything malformed or non-canonical throws {@link BencodeException}. */
    public static BValue decode(byte[] input) {
        Reader r = new Reader(input);
        BValue v = r.value(1);
        if (r.pos != input.length) {
            throw new BencodeException("trailing data at " + r.pos);
        }
        return v;
    }

    private static final class Reader {
        private final byte[] b;
        private int pos;

        Reader(byte[] b) {
            this.b = b;
        }

        private static boolean digit(byte c) {
            return c >= '0' && c <= '9';
        }

        BValue value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new BencodeException("nesting deeper than " + MAX_DEPTH);
            }
            if (pos >= b.length) {
                throw new BencodeException("unexpected end of input");
            }
            byte c = b[pos];
            if (c == 'i') {
                return integer();
            }
            if (digit(c)) {
                return string();
            }
            if (c == 'l') {
                pos++;
                List<BValue> items = new ArrayList<>();
                while (!atEnd()) {
                    items.add(value(depth + 1));
                }
                pos++; // 'e'
                return new BList(items);
            }
            if (c == 'd') {
                pos++;
                BDict.Builder bd = BDict.builder();
                BStr prev = null;
                while (!atEnd()) {
                    if (!digit(b[pos])) {
                        throw new BencodeException("dictionary key must be a string at " + pos);
                    }
                    BStr key = string();
                    if (prev != null && prev.compareTo(key) >= 0) {
                        throw new BencodeException("dictionary keys not strictly ascending at " + pos);
                    }
                    prev = key;
                    bd.put(key, value(depth + 1));
                }
                pos++;
                return bd.build();
            }
            throw new BencodeException("unexpected byte " + (c & 0xff) + " at " + pos);
        }

        /** True when the next byte is the terminating 'e'; throws at end of input. */
        private boolean atEnd() {
            if (pos >= b.length) {
                throw new BencodeException("unterminated container");
            }
            return b[pos] == 'e';
        }

        private BInt integer() {
            int i = pos + 1;
            boolean negative = i < b.length && b[i] == '-';
            if (negative) {
                i++;
            }
            int first = i;
            while (i < b.length && digit(b[i])) {
                i++;
            }
            if (i >= b.length || b[i] != 'e') {
                throw new BencodeException("malformed integer at " + pos);
            }
            int digits = i - first;
            if (digits == 0) {
                throw new BencodeException("integer without digits at " + pos);
            }
            if (b[first] == '0' && (digits > 1 || negative)) {
                throw new BencodeException("leading zero or negative zero at " + pos);
            }
            long value;
            try {
                value = Long.parseLong(new String(b, pos + 1, i - pos - 1, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                throw new BencodeException("integer out of range at " + pos);
            }
            pos = i + 1;
            return new BInt(value);
        }

        private BStr string() {
            int i = pos;
            while (i < b.length && digit(b[i])) {
                i++;
            }
            if (i >= b.length || b[i] != ':') {
                throw new BencodeException("missing ':' in string at " + pos);
            }
            int digits = i - pos;
            if (digits > 10 || (b[pos] == '0' && digits > 1)) {
                throw new BencodeException("bad string length at " + pos);
            }
            long len = Long.parseLong(new String(b, pos, digits, StandardCharsets.US_ASCII));
            int start = i + 1;
            if (len > b.length - start) {
                throw new BencodeException("string longer than input at " + pos);
            }
            byte[] copy = java.util.Arrays.copyOfRange(b, start, start + (int) len);
            pos = start + (int) len;
            return new BStr(copy);
        }
    }
}
