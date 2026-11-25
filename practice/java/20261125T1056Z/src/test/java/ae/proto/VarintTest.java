package ae.proto;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VarintTest {
    static byte[] hex(String s) {
        return HexFormat.of().parseHex(s.replace(" ", ""));
    }

    @Test
    void literalEncodings() {
        assertArrayEquals(hex("00"), Varint.encode(0));
        assertArrayEquals(hex("01"), Varint.encode(1));
        assertArrayEquals(hex("7f"), Varint.encode(127));
        assertArrayEquals(hex("80 01"), Varint.encode(128));
        assertArrayEquals(hex("96 01"), Varint.encode(150));
        assertArrayEquals(hex("ac 02"), Varint.encode(300));
        assertArrayEquals(hex("ff ff ff ff ff ff ff ff ff 01"), Varint.encode(-1L));
        assertArrayEquals(hex("ff ff ff ff ff ff ff ff 7f"), Varint.encode(Long.MAX_VALUE));
        assertArrayEquals(hex("80 80 80 80 80 80 80 80 80 01"), Varint.encode(Long.MIN_VALUE));
    }

    @Test
    void sizeMatchesEncodeAtEveryBitLength() {
        for (int bits = 0; bits < 64; bits++) {
            long v = bits == 0 ? 0 : (1L << bits) - 1; // bits ones
            // bits ones need ceil(bits/7) bytes, at least 1 (comment table: 7->1, 8->2, 14->2, 15->3, 63->9)
            int expected = Math.max(1, (bits + 6) / 7);
            assertEquals(expected, Varint.encode(v).length, "bits=" + bits);
            assertEquals(expected, Varint.size(v), "size bits=" + bits);
            long w = 1L << bits; // single bit at position bits -> bits+1 significant bits
            assertEquals((bits + 7) / 7, Varint.size(w), "single bit " + bits);
        }
        assertEquals(10, Varint.size(-1L));
    }

    @Test
    void readReturnsValueAndNextPosition() {
        byte[] b = hex("aa 96 01 05");
        Varint.Read r = Varint.read(b, 1, b.length);
        assertEquals(150, r.value());
        assertEquals(3, r.next());
        assertEquals(5, Varint.read(b, 3, b.length).value());
    }

    @Test
    void paddedVarintIsAccepted() {
        Varint.Read r = Varint.read(hex("81 80 00"), 0, 3);
        assertEquals(1, r.value());
        assertEquals(3, r.next());
    }

    @Test
    void readRespectsEndNotArrayLength() {
        byte[] b = hex("96 01");
        DecodeException e = assertThrows(DecodeException.class, () -> Varint.read(b, 0, 1));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());
        assertEquals(0, e.getOffset());
    }

    @Test
    void truncatedAndEmptyAreErrors() {
        assertThrows(DecodeException.class, () -> Varint.read(new byte[0], 0, 0));
        assertThrows(DecodeException.class, () -> Varint.read(hex("80 80"), 0, 2));
    }

    @Test
    void tenthByteAboveOneOverflows() {
        DecodeException e = assertThrows(DecodeException.class,
                () -> Varint.read(hex("ff ff ff ff ff ff ff ff ff 02"), 0, 10));
        assertTrue(e.getMessage().contains("overflow"), e.getMessage());
        // control: differs only by the 10th byte
        assertEquals(-1L, Varint.read(hex("ff ff ff ff ff ff ff ff ff 01"), 0, 10).value());
    }

    @Test
    void eleventhByteOverflows() {
        assertThrows(DecodeException.class,
                () -> Varint.read(hex("80 80 80 80 80 80 80 80 80 80 00"), 0, 11));
    }

    @Test
    void zigzagLiterals() {
        assertEquals(0, Varint.zigzag32(0));
        assertEquals(1, Varint.zigzag32(-1));
        assertEquals(2, Varint.zigzag32(1));
        assertEquals(3, Varint.zigzag32(-2));
        assertEquals(4294967294L, Varint.zigzag32(Integer.MAX_VALUE));
        assertEquals(4294967295L, Varint.zigzag32(Integer.MIN_VALUE));
        assertEquals(0, Varint.zigzag64(0));
        assertEquals(1, Varint.zigzag64(-1));
        assertEquals(-2L, Varint.zigzag64(Long.MAX_VALUE)); // 0xFFFF...FE
        assertEquals(-1L, Varint.zigzag64(Long.MIN_VALUE)); // 0xFFFF...FF
    }

    @Test
    void zigzagRoundTripsAtExtremes() {
        int[] ints = {0, 1, -1, 2, -2, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE + 1};
        for (int i : ints) assertEquals(i, Varint.unzigzag32(Varint.zigzag32(i)));
        long[] longs = {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1};
        for (long l : longs) assertEquals(l, Varint.unzigzag64(Varint.zigzag64(l)));
    }

    @Test
    void randomEncodingsMatchBigIntegerGroups() {
        Random rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            long v = rnd.nextLong() >>> rnd.nextInt(64);
            BigInteger u = new BigInteger(Long.toUnsignedString(v));
            byte[] expected = new byte[Varint.size(v)];
            for (int k = 0; k < expected.length; k++) {
                int group = u.shiftRight(7 * k).and(BigInteger.valueOf(0x7f)).intValue();
                expected[k] = (byte) (group | (k < expected.length - 1 ? 0x80 : 0));
            }
            byte[] actual = Varint.encode(v);
            assertArrayEquals(expected, actual, Long.toUnsignedString(v));
            assertEquals(v, Varint.read(actual, 0, actual.length).value());
            // the group count itself comes from the bit length of the BigInteger
            assertEquals(Math.max(1, (u.bitLength() + 6) / 7), actual.length);
        }
    }
}
