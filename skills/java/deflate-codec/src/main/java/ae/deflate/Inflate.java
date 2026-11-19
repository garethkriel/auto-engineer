package ae.deflate;

import java.util.Arrays;

/** Raw DEFLATE (RFC 1951) decoder: stored, fixed and dynamic blocks. */
public final class Inflate {
    private Inflate() {}

    /** Decoded bytes plus the number of input bytes consumed, counted from the given offset. */
    public record Result(byte[] data, int consumed) {}

    private static final Huffman FIXED_LIT;
    private static final Huffman FIXED_DIST;

    static {
        int[] lit = new int[288];
        Arrays.fill(lit, 0, 144, 8);
        Arrays.fill(lit, 144, 256, 9);
        Arrays.fill(lit, 256, 280, 7);
        Arrays.fill(lit, 280, 288, 8);
        FIXED_LIT = new Huffman(lit);
        int[] dist = new int[32]; // 30 and 31 are coded but invalid
        Arrays.fill(dist, 5);
        FIXED_DIST = new Huffman(dist);
    }

    /** Growable output that also serves as the back-reference window. */
    private static final class Out {
        byte[] buf = new byte[1024];
        int len;
        final int max;

        Out(int max) {
            this.max = max;
        }

        void reserve(int extra) {
            if (extra > max - len) {
                throw new InflateException("output exceeds limit of " + max + " bytes");
            }
            if (len + extra > buf.length) {
                buf = Arrays.copyOf(buf, (int) Math.min((long) Integer.MAX_VALUE - 8, Math.max(buf.length * 2L, len + (long) extra)));
            }
        }

        void put(int b) {
            reserve(1);
            buf[len++] = (byte) b;
        }
    }

    /**
     * Decodes one DEFLATE stream.
     *
     * @param maxOutput the largest acceptable decoded size; exceeding it raises {@link InflateException}
     */
    public static Result inflate(byte[] in, int offset, int maxOutput) {
        if (maxOutput < 0) {
            throw new IllegalArgumentException("maxOutput < 0");
        }
        BitReader r = new BitReader(in, offset);
        Out out = new Out(maxOutput);
        boolean last;
        do {
            last = r.bits(1) == 1;
            switch (r.bits(2)) {
                case 0 -> stored(r, out);
                case 1 -> codes(r, out, FIXED_LIT, FIXED_DIST);
                case 2 -> dynamic(r, out);
                default -> throw new InflateException("reserved block type 3");
            }
        } while (!last);
        r.alignToByte();
        return new Result(Arrays.copyOf(out.buf, out.len), r.bytePosition() - offset);
    }

    private static void stored(BitReader r, Out out) {
        r.alignToByte();
        if (r.remainingBytes() < 4) {
            throw new InflateException("truncated stored block header");
        }
        int pos = r.bytePosition();
        byte[] d = r.source();
        int len = (d[pos] & 0xFF) | ((d[pos + 1] & 0xFF) << 8);
        int nlen = (d[pos + 2] & 0xFF) | ((d[pos + 3] & 0xFF) << 8);
        if (len != (~nlen & 0xFFFF)) {
            throw new InflateException("stored block length check failed");
        }
        r.skipBytes(4);
        if (r.remainingBytes() < len) {
            throw new InflateException("truncated stored block");
        }
        out.reserve(len);
        System.arraycopy(d, r.bytePosition(), out.buf, out.len, len);
        out.len += len;
        r.skipBytes(len);
    }

    private static void dynamic(BitReader r, Out out) {
        int nlen = r.bits(5) + 257;
        int ndist = r.bits(5) + 1;
        int ncode = r.bits(4) + 4;
        if (nlen > 286 || ndist > 30) {
            throw new InflateException("too many length or distance codes");
        }
        int[] clen = new int[19];
        for (int i = 0; i < ncode; i++) {
            clen[Tables.CODE_LENGTH_ORDER[i]] = r.bits(3);
        }
        Huffman lenCode = new Huffman(clen);
        int[] lengths = new int[nlen + ndist];
        int idx = 0;
        while (idx < lengths.length) {
            int sym = lenCode.decode(r);
            if (sym < 16) {
                lengths[idx++] = sym;
                continue;
            }
            int prev = 0;
            int repeat;
            if (sym == 16) {
                if (idx == 0) {
                    throw new InflateException("repeat with no previous length");
                }
                prev = lengths[idx - 1];
                repeat = 3 + r.bits(2);
            } else if (sym == 17) {
                repeat = 3 + r.bits(3);
            } else {
                repeat = 11 + r.bits(7);
            }
            if (idx + repeat > lengths.length) {
                throw new InflateException("code length repeat overruns the table");
            }
            Arrays.fill(lengths, idx, idx + repeat, prev);
            idx += repeat;
        }
        if (lengths[Tables.END_OF_BLOCK] == 0) {
            throw new InflateException("no end-of-block code");
        }
        Huffman lit = new Huffman(Arrays.copyOfRange(lengths, 0, nlen));
        Huffman dist = new Huffman(Arrays.copyOfRange(lengths, nlen, lengths.length));
        codes(r, out, lit, dist);
    }

    private static void codes(BitReader r, Out out, Huffman lit, Huffman dist) {
        while (true) {
            int sym = lit.decode(r);
            if (sym < 256) {
                out.put(sym);
            } else if (sym == Tables.END_OF_BLOCK) {
                return;
            } else {
                sym -= 257;
                if (sym >= 29) {
                    throw new InflateException("invalid length symbol " + (sym + 257));
                }
                int length = Tables.LEN_BASE[sym] + r.bits(Tables.LEN_EXTRA[sym]);
                int ds = dist.decode(r);
                if (ds >= 30) {
                    throw new InflateException("invalid distance symbol " + ds);
                }
                int distance = Tables.DIST_BASE[ds] + r.bits(Tables.DIST_EXTRA[ds]);
                if (distance > out.len) {
                    throw new InflateException("distance " + distance + " reaches before start of output");
                }
                out.reserve(length);
                for (int i = 0; i < length; i++) { // byte by byte: source and destination may overlap
                    out.buf[out.len] = out.buf[out.len - distance];
                    out.len++;
                }
            }
        }
    }
}
