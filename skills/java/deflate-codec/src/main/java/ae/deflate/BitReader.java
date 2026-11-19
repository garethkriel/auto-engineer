package ae.deflate;

/** Reads bits least-significant-bit first from a byte array. */
final class BitReader {
    private final byte[] data;
    private int pos;
    private int acc;
    private int accBits;

    BitReader(byte[] data, int offset) {
        if (offset < 0 || offset > data.length) {
            throw new IllegalArgumentException("offset out of range: " + offset);
        }
        this.data = data;
        this.pos = offset;
    }

    /** Reads {@code n} bits (0..16). */
    int bits(int n) {
        while (accBits < n) {
            if (pos >= data.length) {
                throw new InflateException("unexpected end of input");
            }
            acc |= (data[pos++] & 0xFF) << accBits;
            accBits += 8;
        }
        int v = acc & ((1 << n) - 1);
        acc >>>= n;
        accBits -= n;
        return v;
    }

    /** Drops the unread bits of the current byte. Bytes are only fetched on demand, so fewer than 8 remain. */
    void alignToByte() {
        acc = 0;
        accBits = 0;
    }

    /** Index of the next unread byte; only meaningful when byte aligned. */
    int bytePosition() {
        return pos;
    }

    int remainingBytes() {
        return data.length - pos;
    }

    byte[] source() {
        return data;
    }

    void skipBytes(int n) {
        pos += n;
    }
}
