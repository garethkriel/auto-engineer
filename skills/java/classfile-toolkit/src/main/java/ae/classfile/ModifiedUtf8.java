package ae.classfile;

import java.io.ByteArrayOutputStream;

/**
 * Strict codec for the JVM's "modified UTF-8" (JVMS 4.4.7). Differences from standard UTF-8:
 * NUL is {@code C0 80}, supplementary characters are two 3-byte surrogate forms and there are
 * no 4-byte sequences. The decoder additionally rejects raw NUL, 0xF0..0xFF, truncated or
 * badly continued sequences and overlong encodings, so {@code encode(decode(x))} equals {@code x}.
 */
public final class ModifiedUtf8 {
    private ModifiedUtf8() {
    }

    public static byte[] encode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != 0 && c < 0x80) {
                out.write(c);
            } else if (c < 0x800) {
                out.write(0xC0 | (c >> 6));
                out.write(0x80 | (c & 0x3F));
            } else {
                out.write(0xE0 | (c >> 12));
                out.write(0x80 | ((c >> 6) & 0x3F));
                out.write(0x80 | (c & 0x3F));
            }
        }
        return out.toByteArray();
    }

    public static String decode(byte[] bytes) {
        return decode(bytes, 0, bytes.length);
    }

    public static String decode(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        int i = off;
        int end = off + len;
        while (i < end) {
            int c = b[i] & 0xFF;
            if (c == 0) {
                throw new ClassFormatException("bad modified UTF-8: raw NUL at " + (i - off));
            }
            if (c < 0x80) {
                sb.append((char) c);
                i++;
            } else if (c >= 0xC0 && c <= 0xDF) {
                int c2 = continuation(b, i + 1, end, off);
                int v = ((c & 0x1F) << 6) | c2;
                if (c == 0xC0 ? c2 != 0 : v < 0x80) {
                    throw new ClassFormatException("bad modified UTF-8: overlong 2-byte form at " + (i - off));
                }
                sb.append((char) v);
                i += 2;
            } else if (c >= 0xE0 && c <= 0xEF) {
                int c2 = continuation(b, i + 1, end, off);
                int c3 = continuation(b, i + 2, end, off);
                int v = ((c & 0x0F) << 12) | (c2 << 6) | c3;
                if (v < 0x800) {
                    throw new ClassFormatException("bad modified UTF-8: overlong 3-byte form at " + (i - off));
                }
                sb.append((char) v);
                i += 3;
            } else {
                throw new ClassFormatException("bad modified UTF-8: illegal lead byte at " + (i - off));
            }
        }
        return sb.toString();
    }

    private static int continuation(byte[] b, int at, int end, int off) {
        if (at >= end) {
            throw new ClassFormatException("bad modified UTF-8: truncated sequence at " + (at - off));
        }
        int c = b[at] & 0xFF;
        if ((c & 0xC0) != 0x80) {
            throw new ClassFormatException("bad modified UTF-8: bad continuation byte at " + (at - off));
        }
        return c & 0x3F;
    }
}
