package ae.net;

/** RFC 1071 Internet checksum and the RFC 1624 incremental update. */
public final class Checksum {
    private Checksum() {
    }

    /** Ones-complement 16-bit checksum of {@code len} bytes at {@code off}; odd lengths are padded with a zero byte. */
    public static int of(byte[] data, int off, int len) {
        long sum = 0;
        int end = off + len;
        int i = off;
        while (i + 1 < end) {
            sum += ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
            i += 2;
        }
        if (i < end) {
            sum += (data[i] & 0xff) << 8;
        }
        return (int) (~fold(sum) & 0xffff);
    }

    /**
     * RFC 1624 eqn 3: the checksum after one 16-bit word changed from {@code oldWord} to {@code newWord}.
     * Equal to a full recompute up to the two representations of ones-complement zero (0x0000 / 0xffff).
     */
    public static int update(int oldChecksum, int oldWord, int newWord) {
        long sum = (~oldChecksum & 0xffff) + (~oldWord & 0xffff) + (newWord & 0xffff);
        return (int) (~fold(sum) & 0xffff);
    }

    private static long fold(long sum) {
        long s = sum;
        while ((s >> 16) != 0) {
            s = (s & 0xffff) + (s >> 16);
        }
        return s;
    }
}
