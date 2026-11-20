package ae.tsdb;

/** MSB-first bit stream reader over a byte array. */
public final class BitReader {
    private final byte[] data;
    private final long totalBits;
    private long pos;

    /** @param data bytes to read; not copied */
    public BitReader(byte[] data) {
        this.data = data;
        this.totalBits = (long) data.length * 8;
    }

    /** @return the next bit @throws CorruptDataException at end of data */
    public int readBit() {
        if (pos >= totalBits) throw new CorruptDataException("unexpected end of data");
        int bit = (data[(int) (pos >>> 3)] >>> (7 - (int) (pos & 7))) & 1;
        pos++;
        return bit;
    }

    /** @return the next {@code n} bits (0..64) as an unsigned number @throws CorruptDataException at end of data */
    public long readBits(int n) {
        if (n < 0 || n > 64) throw new IllegalArgumentException("bad width " + n);
        if (pos + n > totalBits) throw new CorruptDataException("unexpected end of data");
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 1) | readBit();
        return v;
    }

    /** @return bits not yet read */
    public long remainingBits() {
        return totalBits - pos;
    }
}
