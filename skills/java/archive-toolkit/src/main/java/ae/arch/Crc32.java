package ae.arch;

import java.util.Objects;

/** Table-driven CRC-32 (IEEE 802.3, reflected polynomial 0xEDB88320) as used by ZIP. */
public final class Crc32 {
    private static final int[] TABLE = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            }
            TABLE[n] = c;
        }
    }

    private int crc = 0xFFFFFFFF;

    /** Feeds {@code len} bytes of {@code b} starting at {@code off}. */
    public void update(byte[] b, int off, int len) {
        Objects.requireNonNull(b);
        if (off < 0 || len < 0 || off > b.length - len) {
            throw new IllegalArgumentException("range " + off + "+" + len + " outside array of " + b.length);
        }
        int c = crc;
        for (int i = off; i < off + len; i++) {
            c = TABLE[(c ^ b[i]) & 0xFF] ^ (c >>> 8);
        }
        crc = c;
    }

    /** The CRC of everything fed so far, as an unsigned 32-bit value. */
    public long value() {
        return ~crc & 0xFFFFFFFFL;
    }

    public static long of(byte[] b) {
        Crc32 c = new Crc32();
        c.update(b, 0, b.length);
        return c.value();
    }
}
