package ae.deflate;

import java.util.Arrays;

/** Packs bits least-significant-bit first, as DEFLATE requires. */
final class BitWriter {
    private byte[] buf = new byte[64];
    private int len;
    private int acc;
    private int accBits;

    /** Writes the low {@code n} bits of {@code value} (n up to 16), LSB first. */
    void bits(int value, int n) {
        acc |= (value & ((1 << n) - 1)) << accBits;
        accBits += n;
        while (accBits >= 8) {
            put((byte) acc);
            acc >>>= 8;
            accBits -= 8;
        }
    }

    /** Writes a Huffman code of {@code n} bits, most significant bit first. */
    void code(int code, int n) {
        int reversed = 0;
        for (int i = 0; i < n; i++) {
            reversed |= ((code >>> i) & 1) << (n - 1 - i);
        }
        bits(reversed, n);
    }

    void alignToByte() {
        if (accBits > 0) {
            put((byte) acc);
            acc = 0;
            accBits = 0;
        }
    }

    /** Appends raw bytes; the writer must be byte aligned. */
    void bytes(byte[] src, int off, int n) {
        if (accBits != 0) {
            throw new IllegalStateException("not byte aligned");
        }
        ensure(n);
        System.arraycopy(src, off, buf, len, n);
        len += n;
    }

    byte[] toByteArray() {
        BitWriter copy = new BitWriter();
        copy.buf = buf.clone();
        copy.len = len;
        copy.acc = acc;
        copy.accBits = accBits;
        copy.alignToByte();
        return Arrays.copyOf(copy.buf, copy.len);
    }

    private void put(byte b) {
        ensure(1);
        buf[len++] = b;
    }

    private void ensure(int extra) {
        if (len + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
        }
    }
}
