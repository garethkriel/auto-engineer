package ae.sketch;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Hash64Test {
    @Test
    void sameInputSameSeedIsDeterministic() {
        assertEquals(Hash64.hash("hello", 7), Hash64.hash("hello", 7));
        assertEquals(Hash64.hash("hello", 7), Hash64.hash("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), 7));
    }

    @Test
    void seedAndInputBothChangeTheHash() {
        assertNotEquals(Hash64.hash("hello", 1), Hash64.hash("hello", 2));
        assertNotEquals(Hash64.hash("hello", 1), Hash64.hash("hellp", 1));
        assertNotEquals(Hash64.hash(new byte[0], 1), Hash64.hash(new byte[1], 1));
    }

    @Test
    void trailingZeroBytesAreDistinguishedByLength() {
        assertNotEquals(Hash64.hash(new byte[] {1}, 0), Hash64.hash(new byte[] {1, 0}, 0));
    }

    @Test
    void flippingOneInputBitFlipsAboutHalfTheOutputBits() {
        long total = 0;
        int trials = 2000;
        for (int i = 0; i < trials; i++) {
            byte[] a = ("key-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] b = a.clone();
            b[b.length - 1] ^= 1;
            total += Long.bitCount(Hash64.hash(a, 3) ^ Hash64.hash(b, 3));
        }
        double avg = total / (double) trials;
        assertTrue(avg > 30 && avg < 34, "average flipped bits was " + avg);
    }

    @Test
    void nullInputRejected() {
        assertThrows(NullPointerException.class, () -> Hash64.hash((String) null, 0));
        assertThrows(NullPointerException.class, () -> Hash64.hash((byte[]) null, 0));
    }
}
