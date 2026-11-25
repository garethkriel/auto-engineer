package ae.proto;

import java.io.ByteArrayOutputStream;

/** Base-128 varints (unsigned LEB128 over 64 bits) and zigzag mapping. */
public final class Varint {
    private Varint() {}

    /** A decoded varint and the position just after it. */
    public record Read(long value, int next) {}

    /** Encodes {@code v}, treated as an unsigned 64-bit number, in 1..10 bytes. */
    public static byte[] encode(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(10);
        write(o, v);
        return o.toByteArray();
    }

    static void write(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    /** Number of bytes {@link #encode} produces. */
    public static int size(long v) {
        int bits = 64 - Long.numberOfLeadingZeros(v);
        return Math.max(1, (bits + 6) / 7);
    }

    /**
     * Reads a varint from {@code b[pos..end)}. Padded (non-canonical) forms are accepted; more than 64
     * significant bits or a missing terminator is an error whose offset is the varint's first byte.
     */
    public static Read read(byte[] b, int pos, int end) {
        long result = 0;
        for (int i = 0; i < 10; i++) {
            int at = pos + i;
            if (at >= end) throw new DecodeException("truncated varint", pos);
            int x = b[at] & 0xFF;
            if (i == 9 && x > 1) throw new DecodeException("varint overflow", pos);
            result |= (long) (x & 0x7F) << (7 * i);
            if ((x & 0x80) == 0) return new Read(result, at + 1);
        }
        throw new DecodeException("varint overflow", pos); // unreachable: the tenth byte is <= 1
    }

    /** Zigzag of a 32-bit value as an unsigned 32-bit number held in a long. */
    public static long zigzag32(int n) {
        return ((n << 1) ^ (n >> 31)) & 0xFFFFFFFFL;
    }

    public static int unzigzag32(long v) {
        int u = (int) v;
        return (u >>> 1) ^ -(u & 1);
    }

    public static long zigzag64(long n) {
        return (n << 1) ^ (n >> 63);
    }

    public static long unzigzag64(long v) {
        return (v >>> 1) ^ -(v & 1);
    }
}
