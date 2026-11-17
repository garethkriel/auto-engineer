package ae.tsblock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** LEB128-style variable length integers with zigzag mapping for signed values. */
final class Varint {
    private Varint() {}

    /** Maps signed to unsigned so small magnitudes (either sign) get small codes. */
    static long zigzag(long n) {
        return (n << 1) ^ (n >> 63);
    }

    static long unzigzag(long u) {
        return (u >>> 1) ^ -(u & 1);
    }

    /** Writes the 64 bits of {@code v}, interpreted as unsigned, in 1 to 10 bytes. */
    static void writeUnsigned(ByteArrayOutputStream out, long v) {
        long rest = v;
        while ((rest & ~0x7FL) != 0) {
            out.write((int) ((rest & 0x7F) | 0x80));
            rest >>>= 7;
        }
        out.write((int) rest);
    }

    static void writeSigned(ByteArrayOutputStream out, long v) {
        writeUnsigned(out, zigzag(v));
    }

    static long readUnsigned(ByteBuffer buf) throws CorruptFileException {
        long result = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            if (!buf.hasRemaining()) {
                throw new CorruptFileException("truncated varint");
            }
            int b = buf.get() & 0xFF;
            if (shift == 63 && (b & 0xFE) != 0) {
                throw new CorruptFileException("varint overflows 64 bits");
            }
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new CorruptFileException("varint too long");
    }

    static long readSigned(ByteBuffer buf) throws CorruptFileException {
        return unzigzag(readUnsigned(buf));
    }
}
