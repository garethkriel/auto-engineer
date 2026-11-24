package ae.chord;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RingTest {

    @Test
    void arithmeticWrapsAroundTheCircle() {
        Ring r = new Ring(4);
        assertEquals(16, r.size());
        assertEquals(3, r.mod(19));
        assertEquals(15, r.mod(-1));
        assertEquals(1, r.add(14, 3));
        assertEquals(0, r.dist(5, 5));
        assertEquals(11, r.dist(14, 9));
        assertEquals(5, r.dist(9, 14));
    }

    @Test
    void rejectsBadBitCounts() {
        assertThrows(IllegalArgumentException.class, () -> new Ring(0));
        assertThrows(IllegalArgumentException.class, () -> new Ring(41));
        assertDoesNotThrow(() -> new Ring(40));
    }

    @Test
    void intervalsMatchAWalkingOracleExhaustively() {
        Ring r = new Ring(4);
        for (long a = 0; a < 16; a++) {
            for (long b = 0; b < 16; b++) {
                for (long x = 0; x < 16; x++) {
                    // walk clockwise from a (exclusive) until b, collecting the visited ids
                    boolean open = false;
                    boolean half = false;
                    if (a == b) {
                        open = x != a;
                        half = true;
                    } else {
                        long p = (a + 1) % 16;
                        while (true) {
                            if (p == x) {
                                half = true;
                                open = p != b;
                            }
                            if (p == b) {
                                break;
                            }
                            p = (p + 1) % 16;
                        }
                    }
                    String at = "x=" + x + " a=" + a + " b=" + b;
                    assertEquals(open, r.inOpen(x, a, b), "open " + at);
                    assertEquals(half, r.inHalfOpen(x, a, b), "halfOpen " + at);
                }
            }
        }
    }

    @Test
    void fingerStartsAddPowersOfTwo() {
        Ring r = new Ring(6);
        assertEquals(9, r.fingerStart(8, 0));
        assertEquals(10, r.fingerStart(8, 1));
        assertEquals(40, r.fingerStart(8, 5));
        assertEquals(24, r.fingerStart(56, 5));
        assertThrows(IllegalArgumentException.class, () -> r.fingerStart(8, 6));
    }

    @Test
    void hashUsesTheTopBitsOfSha1() {
        // SHA-1("abc") = a9993e36 4706816a ...; top 16 bits are 0xa999, top 8 bits 0xa9, top 4 bits 0xa
        assertEquals(0xa999, new Ring(16).hash("abc"));
        assertEquals(0xa9, new Ring(8).hash("abc"));
        assertEquals(0xa, new Ring(4).hash("abc"));
        // SHA-1("") = da39a3ee ...
        assertEquals(0xda39, new Ring(16).hash(""));
    }
}
