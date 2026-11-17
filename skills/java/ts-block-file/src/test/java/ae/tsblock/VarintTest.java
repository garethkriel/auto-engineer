package ae.tsblock;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class VarintTest {
    @Test
    void zigzagKnownValues() {
        assertEquals(0L, Varint.zigzag(0));
        assertEquals(1L, Varint.zigzag(-1));
        assertEquals(2L, Varint.zigzag(1));
        assertEquals(3L, Varint.zigzag(-2));
        assertEquals(-1L, Varint.zigzag(Long.MIN_VALUE));      // 0xFFFF...F
        assertEquals(-2L, Varint.zigzag(Long.MAX_VALUE));      // 0xFFFF...E
    }

    @Test
    void encodedLengths() {
        assertEquals(1, encode(0).length);
        assertEquals(1, encode(127).length);
        assertEquals(2, encode(128).length);
        assertEquals(10, encode(-1L).length); // all 64 bits set
    }

    @Test
    void roundTripExtremes() throws CorruptFileException {
        long[] vals = {0, 1, -1, 127, 128, 16383, 16384, Long.MAX_VALUE, Long.MIN_VALUE, 123456789012345L};
        for (long v : vals) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Varint.writeSigned(out, v);
            ByteBuffer buf = ByteBuffer.wrap(out.toByteArray());
            assertEquals(v, Varint.readSigned(buf));
            assertFalse(buf.hasRemaining());
        }
    }

    @Test
    void truncatedInputThrows() {
        ByteBuffer buf = ByteBuffer.wrap(new byte[] {(byte) 0x80, (byte) 0x80});
        assertThrows(CorruptFileException.class, () -> Varint.readUnsigned(buf));
    }

    @Test
    void overlongVarintThrows() {
        byte[] eleven = new byte[11];
        java.util.Arrays.fill(eleven, (byte) 0x80);
        eleven[10] = 0x01;
        assertThrows(CorruptFileException.class, () -> Varint.readUnsigned(ByteBuffer.wrap(eleven)));
    }

    private static byte[] encode(long unsigned) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Varint.writeUnsigned(out, unsigned);
        return out.toByteArray();
    }
}
