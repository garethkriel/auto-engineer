package ae.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ChecksumTest {
    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    /** In ones-complement arithmetic 0x0000 and 0xffff are the same number. */
    private static int norm(int checksum) {
        return checksum == 0xffff ? 0 : checksum;
    }

    @Test
    void rfc1071Example() {
        byte[] d = hex("0001f203f4f5f6f7");
        assertEquals(0x220d, Checksum.of(d, 0, d.length));
    }

    @Test
    void oddLengthIsPaddedWithZeroOnTheRight() {
        assertEquals(0xfeff, Checksum.of(new byte[] {1}, 0, 1));
    }

    @Test
    void emptyInputIsAllOnes() {
        assertEquals(0xffff, Checksum.of(new byte[0], 0, 0));
    }

    @Test
    void wikipediaHeaderWithZeroChecksumField() {
        byte[] h = hex("450000730000400040110000c0a80001c0a800c7");
        assertEquals(0xb861, Checksum.of(h, 0, 20));
    }

    @Test
    void offsetAndLengthSelectTheWindow() {
        byte[] h = hex("ffff" + "0001f203f4f5f6f7" + "ffff");
        assertEquals(0x220d, Checksum.of(h, 2, 8));
    }

    @Test
    void carriesFoldRepeatedly() {
        // 0xffff * 3 words: sum 0x2fffd -> fold 0xffff -> checksum 0.
        byte[] d = hex("ffffffffffff");
        assertEquals(0x0000, Checksum.of(d, 0, 6));
    }

    @Test
    void verifyingAWholeBlockWithItsChecksumGivesZero() {
        byte[] h = hex("45000073000040004011b861c0a80001c0a800c7");
        assertEquals(0, Checksum.of(h, 0, 20));
    }

    @Test
    void incrementalUpdateMatchesRecomputeOnRandomData() {
        Random r = new Random(42);
        for (int round = 0; round < 500; round++) {
            byte[] d = new byte[2 * (1 + r.nextInt(20))];
            r.nextBytes(d);
            int hc = Checksum.of(d, 0, d.length);
            int idx = 2 * r.nextInt(d.length / 2);
            int oldWord = ((d[idx] & 0xff) << 8) | (d[idx + 1] & 0xff);
            int newWord = r.nextInt(0x10000);
            d[idx] = (byte) (newWord >> 8);
            d[idx + 1] = (byte) newWord;
            assertEquals(norm(Checksum.of(d, 0, d.length)), norm(Checksum.update(hc, oldWord, newWord)),
                    "round " + round + " idx " + idx);
        }
    }

    @Test
    void incrementalUpdateRfc1624Corner() {
        // Update where the old and new words straddle the 0xffff/0x0000 representations of ones-complement zero.
        byte[] d = hex("ffff" + "0000");
        int hc = Checksum.of(d, 0, 4);
        d[0] = 0;
        d[1] = 0;
        assertEquals(0xffff, Checksum.of(d, 0, 4), "all-zero data has checksum 0xffff");
        assertEquals(0x0000, Checksum.update(hc, 0xffff, 0x0000),
                "RFC 1624 eqn 3 yields the other zero representation here");
        assertEquals(0, norm(Checksum.update(hc, 0xffff, 0x0000)) ^ norm(Checksum.of(d, 0, 4)));
    }
}
