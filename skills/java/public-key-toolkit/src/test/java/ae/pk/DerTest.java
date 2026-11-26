package ae.pk;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DerTest {
    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    @Test
    void encodesMinimalIntegers() {
        // r = 1, s = 0x80 needs a leading zero (otherwise negative)
        assertArrayEquals(b(0x30, 0x07, 2, 1, 1, 2, 2, 0, 0x80),
            Der.encode(new Ecdsa.Signature(BigInteger.ONE, BigInteger.valueOf(0x80))));
        assertArrayEquals(b(0x30, 0x06, 2, 1, 0x7f, 2, 1, 0x7f),
            Der.encode(new Ecdsa.Signature(BigInteger.valueOf(0x7f), BigInteger.valueOf(0x7f))));
        assertArrayEquals(b(0x30, 0x08, 2, 2, 1, 0, 2, 2, 0x7f, 0xff),
            Der.encode(new Ecdsa.Signature(BigInteger.valueOf(256), BigInteger.valueOf(0x7fff))));
    }

    @Test
    void roundTripRandom() {
        Random rnd = new Random(2);
        for (int i = 0; i < 200; i++) {
            Ecdsa.Signature s = new Ecdsa.Signature(new BigInteger(1 + rnd.nextInt(256), rnd).add(BigInteger.ONE),
                new BigInteger(1 + rnd.nextInt(256), rnd).add(BigInteger.ONE));
            assertEquals(s, Der.decode(Der.encode(s)));
        }
    }

    @Test
    void decodeRejectsCraftedDefects() {
        byte[] ok = b(0x30, 0x06, 2, 1, 5, 2, 1, 6);
        assertEquals(new Ecdsa.Signature(BigInteger.valueOf(5), BigInteger.valueOf(6)), Der.decode(ok), "control");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b()), "empty");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x31, 6, 2, 1, 5, 2, 1, 6)), "wrong sequence tag");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 6, 3, 1, 5, 2, 1, 6)), "wrong int tag r");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 6, 2, 1, 5, 3, 1, 6)), "wrong int tag s");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 6, 2, 1, 5, 2, 1, 6, 0)), "trailing byte");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 5, 2, 1, 5, 2, 1, 6)), "seq length short");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 7, 2, 1, 5, 2, 1, 6)), "seq length long");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 0x81, 6, 2, 1, 5, 2, 1, 6)), "long-form length");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 7, 2, 2, 0, 5, 2, 1, 6)), "padded zero");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 6, 2, 1, 0x85, 2, 1, 6)), "negative r");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 5, 2, 0, 2, 1, 6)), "zero-length int");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 6, 2, 1, 5, 2, 1)), "truncated");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 6, 2, 3, 5, 2, 1, 6)), "int overruns");
        assertThrows(IllegalArgumentException.class, () -> Der.decode(b(0x30, 0)), "empty sequence");
        // 0x00 0x80 is a legal minimal positive 128: control for the padded-zero case
        assertEquals(BigInteger.valueOf(128),
            Der.decode(b(0x30, 7, 2, 2, 0, 0x80, 2, 1, 6)).r());
    }

    @Test
    void everyTruncationAndByteFlipIsRejectedOrCanonical() {
        Random rnd = new Random(4);
        for (int round = 0; round < 6; round++) {
            Ecdsa.Signature s = new Ecdsa.Signature(new BigInteger(255 + rnd.nextInt(2), rnd).add(BigInteger.ONE),
                new BigInteger(250, rnd).add(BigInteger.ONE));
            byte[] enc = Der.encode(s);
            for (int len = 0; len < enc.length; len++) {
                byte[] cut = Arrays.copyOf(enc, len);
                assertThrows(IllegalArgumentException.class, () -> Der.decode(cut), "cut to " + len);
            }
            int accepted = 0;
            for (int i = 0; i < enc.length; i++) {
                for (int bit = 0; bit < 8; bit++) {
                    byte[] m = enc.clone();
                    m[i] ^= (byte) (1 << bit);
                    try {
                        Ecdsa.Signature t = Der.decode(m);
                        assertArrayEquals(m, Der.encode(t), "accepted input must be canonical");
                        accepted++;
                    } catch (IllegalArgumentException expected) {
                        // rejected
                    }
                }
            }
            assertTrue(accepted > 0, "flips inside the integer bodies stay valid");
        }
    }

    @Test
    void bodyLengthOf128MustNotBeAcceptedWithoutLongForm() {
        // r: 61 bytes, s: 63 bytes -> body = 2+61+2+63 = 128 bytes, which DER would encode as 81 80 (not producible here)
        byte[] enc = new byte[2 + 128];
        enc[0] = 0x30;
        enc[1] = (byte) 0x80; // indefinite/long-form marker: never valid in our strict subset
        enc[2] = 2;
        enc[3] = 61;
        enc[4] = 0x11;
        enc[4 + 61] = 2;
        enc[5 + 61] = 63;
        enc[6 + 61] = 0x22;
        assertThrows(IllegalArgumentException.class, () -> Der.decode(enc));
        // control: the same integers with the 126-byte body (r 60, s 62) that does fit short form
        byte[] ok = new byte[2 + 126];
        ok[0] = 0x30;
        ok[1] = 126;
        ok[2] = 2;
        ok[3] = 60;
        ok[4] = 0x11;
        ok[4 + 60] = 2;
        ok[5 + 60] = 62;
        ok[6 + 60] = 0x22;
        assertEquals(BigInteger.ONE.shiftLeft(8 * 59).multiply(BigInteger.valueOf(0x11)), Der.decode(ok).r());
    }
}
