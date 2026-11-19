package ae.deflate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HuffmanTest {
    /** RFC 1951 section 3.2.2 example: lengths (3,3,3,3,3,2,4,4) give A=010 ... F=00 G=1110 H=1111. */
    @Test
    void canonicalCodesMatchTheRfcExample() {
        Huffman h = new Huffman(new int[] {3, 3, 3, 3, 3, 2, 4, 4});
        String[] codes = {"010", "011", "100", "101", "110", "00", "1110", "1111"};
        for (int sym = 0; sym < codes.length; sym++) {
            BitWriter w = new BitWriter();
            for (char c : codes[sym].toCharArray()) {
                w.bits(c - '0', 1);
            }
            BitReader r = new BitReader(w.toByteArray(), 0);
            assertEquals(sym, h.decode(r), "symbol " + sym + " code " + codes[sym]);
        }
    }

    @Test
    void oversubscribedLengthsAreRejected() {
        assertThrows(InflateException.class, () -> new Huffman(new int[] {1, 1, 1}));
    }

    @Test
    void incompleteSetsAreRejectedExceptTheSingleOneBitCode() {
        assertThrows(InflateException.class, () -> new Huffman(new int[] {2, 2, 2}));
        Huffman single = new Huffman(new int[] {0, 0, 1});
        BitReader r = new BitReader(new byte[] {0}, 0);
        assertEquals(2, single.decode(r));
        BitReader bad = new BitReader(new byte[] {1}, 0); // bit 1 is not a code
        assertThrows(InflateException.class, () -> single.decode(bad));
    }

    @Test
    void lengthAboveFifteenIsRejected() {
        assertThrows(InflateException.class, () -> new Huffman(new int[] {16, 1}));
    }
}
