package ae.utf;

import java.util.Objects;

/** UTF-16 code-unit codec on byte arrays. */
public final class Utf16 {
    /** Result of byte-order-mark detection. */
    public enum Bom { NONE, BIG_ENDIAN, LITTLE_ENDIAN }

    private Utf16() { }

    public static Bom detectBom(byte[] b) {
        Objects.requireNonNull(b, "b");
        if (b.length >= 2) {
            if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
                return Bom.BIG_ENDIAN;
            }
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
                return Bom.LITTLE_ENDIAN;
            }
        }
        return Bom.NONE;
    }

    public static byte[] encode(int[] cps, boolean bigEndian, boolean bom) {
        Objects.requireNonNull(cps, "cps");
        int units = bom ? 1 : 0;
        for (int i = 0; i < cps.length; i++) {
            check(cps[i], i);
            units += cps[i] >= 0x10000 ? 2 : 1;
        }
        byte[] out = new byte[units * 2];
        int p = 0;
        if (bom) {
            p = put(out, p, 0xFEFF, bigEndian);
        }
        for (int c : cps) {
            if (c >= 0x10000) {
                int v = c - 0x10000;
                p = put(out, p, 0xD800 | (v >> 10), bigEndian);
                p = put(out, p, 0xDC00 | (v & 0x3FF), bigEndian);
            } else {
                p = put(out, p, c, bigEndian);
            }
        }
        return out;
    }

    /** Strict decode; a leading BOM is NOT stripped (it decodes to U+FEFF). */
    public static int[] decode(byte[] b, boolean bigEndian) {
        Objects.requireNonNull(b, "b");
        if ((b.length & 1) != 0) {
            throw new CodecException("odd number of bytes", b.length - 1);
        }
        int[] tmp = new int[b.length / 2];
        int n = 0;
        for (int i = 0; i < b.length; i += 2) {
            int u = unit(b, i, bigEndian);
            if (u >= 0xD800 && u <= 0xDBFF) {
                if (i + 3 >= b.length) {
                    throw new CodecException("high surrogate at end", i);
                }
                int lo = unit(b, i + 2, bigEndian);
                if (lo < 0xDC00 || lo > 0xDFFF) {
                    throw new CodecException("high surrogate not followed by low", i);
                }
                tmp[n++] = 0x10000 + ((u - 0xD800) << 10) + (lo - 0xDC00);
                i += 2;
            } else if (u >= 0xDC00 && u <= 0xDFFF) {
                throw new CodecException("lone low surrogate", i);
            } else {
                tmp[n++] = u;
            }
        }
        return java.util.Arrays.copyOf(tmp, n);
    }

    private static void check(int c, int index) {
        if (c < 0 || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) {
            throw new CodecException("not a Unicode scalar value: " + c, index);
        }
    }

    private static int unit(byte[] b, int i, boolean be) {
        int hi = b[i] & 0xFF;
        int lo = b[i + 1] & 0xFF;
        return be ? (hi << 8) | lo : (lo << 8) | hi;
    }

    private static int put(byte[] out, int p, int unit, boolean be) {
        out[p] = (byte) (be ? unit >> 8 : unit);
        out[p + 1] = (byte) (be ? unit : unit >> 8);
        return p + 2;
    }
}
