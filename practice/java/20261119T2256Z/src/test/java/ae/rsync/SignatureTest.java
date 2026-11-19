package ae.rsync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class SignatureTest {
    @Test
    void blockLayoutWithShortTail() throws Exception {
        byte[] d = TestData.random(2, 25);
        Signature s = Signature.of(d, 10);
        assertEquals(10, s.blockSize());
        assertEquals(25, s.fileLength());
        assertEquals(3, s.blocks().size());
        assertEquals(Rolling.weak(d, 0, 10), s.blocks().get(0).weak());
        assertEquals(Rolling.weak(d, 20, 5), s.blocks().get(2).weak());
        byte[] expectedStrong = MessageDigest.getInstance("SHA-256").digest(TestData.slice(d, 20, 25));
        assertArrayEquals(expectedStrong, s.blocks().get(2).strong());
        assertEquals(5, s.lastBlockLength());
        assertEquals(10, s.lengthOfBlock(0));
        assertEquals(5, s.lengthOfBlock(2));
    }

    @Test
    void exactMultipleHasFullLastBlock() {
        Signature s = Signature.of(TestData.random(3, 30), 10);
        assertEquals(3, s.blocks().size());
        assertEquals(10, s.lastBlockLength());
    }

    @Test
    void emptyBasisHasNoBlocks() {
        Signature s = Signature.of(new byte[0], 16);
        assertEquals(0, s.blocks().size());
        assertEquals(0, s.fileLength());
    }

    @Test
    void roundTripsThroughBytes() throws Exception {
        for (int n : new int[] {0, 1, 9, 10, 11, 100, 1234}) {
            Signature s = Signature.of(TestData.random(n, n), 10);
            Signature back = Signature.parse(s.toBytes());
            assertEquals(s, back, "n=" + n);
            assertArrayEquals(s.toBytes(), back.toBytes());
        }
    }

    @Test
    void wireSizeIsHeaderPlusBlocksPlusCrc() {
        Signature s = Signature.of(TestData.random(4, 95), 10); // 10 blocks
        assertEquals(4 + 4 + 8 + 4 + 10 * (4 + 32) + 4, s.toBytes().length);
    }

    @Test
    void rejectsBadBlockSize() {
        assertThrows(IllegalArgumentException.class, () -> Signature.of(new byte[3], 0));
        assertThrows(IllegalArgumentException.class, () -> Signature.of(new byte[3], -5));
    }

    @Test
    void everyTruncationIsRejected() {
        byte[] wire = Signature.of(TestData.random(5, 55), 10).toBytes();
        for (int cut = 0; cut < wire.length; cut++) {
            int n = cut;
            assertThrows(FormatException.class, () -> Signature.parse(TestData.slice(wire, 0, n)), "cut " + n);
        }
    }

    @Test
    void everySingleByteFlipIsRejected() {
        byte[] wire = Signature.of(TestData.random(6, 55), 10).toBytes();
        for (int i = 0; i < wire.length; i++) {
            byte[] bad = wire.clone();
            bad[i] ^= 0x40;
            int at = i;
            assertThrows(FormatException.class, () -> Signature.parse(bad), "flip at " + at);
        }
    }

    @Test
    void trailingBytesRejected() {
        byte[] wire = Signature.of(TestData.random(7, 20), 10).toBytes();
        assertThrows(FormatException.class, () -> Signature.parse(TestData.concat(wire, new byte[] {0})));
    }

    @Test
    void resealedHeaderWithWrongBlockCountIsRejectedForStructure() {
        // Valid CRC but blockCount does not match fileLength/blockSize: must fail on structure, not CRC.
        byte[] wire = Signature.of(TestData.random(8, 30), 10).toBytes();
        byte[] bad = wire.clone();
        bad[4 + 4 + 8 + 3] = 5; // blockCount low byte 3 -> 5 (u32 big-endian at offset 16)
        WireTestSupport.reseal(bad);
        FormatException e = assertThrows(FormatException.class, () -> Signature.parse(bad));
        assertTrue(e.getMessage().contains("block count"), e.getMessage());
        // control: resealing an untouched image still parses
        byte[] ok = wire.clone();
        WireTestSupport.reseal(ok);
        assertDoesNotThrowParse(ok);
    }

    private static void assertDoesNotThrowParse(byte[] b) {
        try {
            Signature.parse(b);
        } catch (FormatException e) {
            throw new AssertionError("control image must parse", e);
        }
    }
}
