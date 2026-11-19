package ae.deflate;

/** Canonical Huffman decoder built from code lengths (counts + sorted symbols, as in zlib's puff). */
final class Huffman {
    static final int MAX_BITS = 15;

    private final int[] count = new int[MAX_BITS + 1];
    private final int[] symbol;

    Huffman(int[] lengths) {
        symbol = new int[lengths.length];
        for (int len : lengths) {
            if (len < 0 || len > MAX_BITS) {
                throw new InflateException("code length out of range: " + len);
            }
            count[len]++;
        }
        int used = lengths.length - count[0];
        int left = 1;
        for (int len = 1; len <= MAX_BITS; len++) {
            left = (left << 1) - count[len];
            if (left < 0) {
                throw new InflateException("over-subscribed code lengths");
            }
        }
        boolean singleOneBit = used == 1 && count[1] == 1;
        if (left > 0 && used > 0 && !singleOneBit) {
            throw new InflateException("incomplete code lengths");
        }
        int[] offs = new int[MAX_BITS + 2];
        for (int len = 1; len <= MAX_BITS; len++) {
            offs[len + 1] = offs[len] + count[len];
        }
        for (int sym = 0; sym < lengths.length; sym++) {
            if (lengths[sym] != 0) {
                symbol[offs[lengths[sym]]++] = sym;
            }
        }
    }

    int decode(BitReader in) {
        int code = 0;
        int first = 0;
        int index = 0;
        for (int len = 1; len <= MAX_BITS; len++) {
            code |= in.bits(1);
            int c = count[len];
            if (code - c < first) {
                return symbol[index + (code - first)];
            }
            index += c;
            first += c;
            first <<= 1;
            code <<= 1;
        }
        throw new InflateException("invalid Huffman code");
    }
}
