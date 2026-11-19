package ae.deflate;

import java.util.Arrays;

/**
 * DEFLATE compressor: LZ77 (32 KiB window, hash chains) emitted as a single fixed-Huffman block, or as stored blocks
 * when that is smaller or {@link Level#STORED} is requested.
 */
public final class Deflate {
    private Deflate() {}

    /** Effort levels; the number is the maximum hash-chain length searched per position. */
    public enum Level {
        STORED(0), FAST(4), DEFAULT(64), BEST(2048);

        final int maxChain;

        Level(int maxChain) {
            this.maxChain = maxChain;
        }
    }

    private static final int MAX_STORED = 65_535;
    private static final int HASH_BITS = 15;
    private static final int WMASK = Tables.WINDOW - 1;

    public static byte[] compress(byte[] data, Level level) {
        if (level == Level.STORED) {
            return stored(data);
        }
        byte[] fixed = fixed(data, level.maxChain);
        // stored size: 5 bytes of header per block (at least one block, even for empty input)
        long storedSize = data.length + 5L * Math.max(1, (data.length + MAX_STORED - 1) / MAX_STORED);
        return fixed.length <= storedSize ? fixed : stored(data);
    }

    private static byte[] stored(byte[] data) {
        BitWriter w = new BitWriter();
        int off = 0;
        do {
            int n = Math.min(MAX_STORED, data.length - off);
            boolean last = off + n >= data.length;
            w.bits(last ? 1 : 0, 1);
            w.bits(0, 2);
            w.alignToByte();
            w.bits(n, 16);
            w.bits(~n & 0xFFFF, 16);
            w.bytes(data, off, n);
            off += n;
        } while (off < data.length);
        return w.toByteArray();
    }

    private static byte[] fixed(byte[] d, int maxChain) {
        BitWriter w = new BitWriter();
        w.bits(1, 1); // BFINAL
        w.bits(1, 2); // BTYPE = fixed
        int[] head = new int[1 << HASH_BITS];
        Arrays.fill(head, -1);
        int[] prev = new int[Tables.WINDOW];
        int n = d.length;
        int i = 0;
        while (i < n) {
            int bestLen = 0;
            int bestDist = 0;
            if (i + Tables.MIN_MATCH <= n) {
                int h = hash(d, i);
                int cand = head[h];
                int chain = maxChain;
                int limit = Math.min(Tables.MAX_MATCH, n - i);
                while (cand >= 0 && i - cand <= Tables.WINDOW && chain-- > 0) {
                    int l = 0;
                    while (l < limit && d[cand + l] == d[i + l]) {
                        l++;
                    }
                    if (l > bestLen) {
                        bestLen = l;
                        bestDist = i - cand;
                        if (l == limit) {
                            break;
                        }
                    }
                    int next = prev[cand & WMASK];
                    if (next >= cand) {
                        break; // slot was recycled for a newer position
                    }
                    cand = next;
                }
            }
            int advance;
            if (bestLen >= Tables.MIN_MATCH) {
                writeMatch(w, bestLen, bestDist);
                advance = bestLen;
            } else {
                writeLiteral(w, d[i] & 0xFF);
                advance = 1;
            }
            for (int k = 0; k < advance; k++, i++) {
                if (i + Tables.MIN_MATCH <= n) {
                    int h = hash(d, i);
                    prev[i & WMASK] = head[h];
                    head[h] = i;
                }
            }
        }
        writeLitLen(w, Tables.END_OF_BLOCK);
        return w.toByteArray();
    }

    private static int hash(byte[] d, int i) {
        int v = (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16);
        return (v * 0x9E3779B1) >>> (32 - HASH_BITS);
    }

    private static void writeLiteral(BitWriter w, int b) {
        writeLitLen(w, b);
    }

    /** Fixed literal/length code: 0-143 8 bits, 144-255 9 bits, 256-279 7 bits, 280-287 8 bits. */
    private static void writeLitLen(BitWriter w, int sym) {
        if (sym < 144) {
            w.code(0x30 + sym, 8);
        } else if (sym < 256) {
            w.code(0x190 + sym - 144, 9);
        } else if (sym < 280) {
            w.code(sym - 256, 7);
        } else {
            w.code(0xC0 + sym - 280, 8);
        }
    }

    private static void writeMatch(BitWriter w, int length, int distance) {
        int ls = Tables.LEN_BASE.length - 1;
        while (Tables.LEN_BASE[ls] > length) {
            ls--;
        }
        writeLitLen(w, 257 + ls);
        w.bits(length - Tables.LEN_BASE[ls], Tables.LEN_EXTRA[ls]);
        int ds = Tables.DIST_BASE.length - 1;
        while (Tables.DIST_BASE[ds] > distance) {
            ds--;
        }
        w.code(ds, 5);
        w.bits(distance - Tables.DIST_BASE[ds], Tables.DIST_EXTRA[ds]);
    }
}
