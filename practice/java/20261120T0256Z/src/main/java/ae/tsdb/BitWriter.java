package ae.tsdb;

import java.util.Arrays;

/** MSB-first bit stream writer. */
public final class BitWriter {
    private byte[] buf = new byte[32];
    private long bits;

    /** Appends one bit (0 or 1). */
    public void writeBit(int bit) {
        int idx = (int) (bits >>> 3);
        if (idx == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
        if (bit != 0) buf[idx] |= (byte) (0x80 >>> (bits & 7));
        bits++;
    }

    /**
     * Appends the low {@code n} bits of {@code value}, most significant first.
     *
     * @throws IllegalArgumentException if n is outside 0..64 or value does not fit in n bits
     */
    public void writeBits(long value, int n) {
        if (n < 0 || n > 64) throw new IllegalArgumentException("bad width " + n);
        if (n < 64 && (value >>> n) != 0) throw new IllegalArgumentException(value + " does not fit in " + n + " bits");
        for (int i = n - 1; i >= 0; i--) writeBit((int) ((value >>> i) & 1L));
    }

    /** @return number of bits written so far */
    public long bitLength() {
        return bits;
    }

    /** @return the stream padded with zero bits to a whole number of bytes */
    public byte[] toByteArray() {
        return Arrays.copyOf(buf, (int) ((bits + 7) >>> 3));
    }
}
