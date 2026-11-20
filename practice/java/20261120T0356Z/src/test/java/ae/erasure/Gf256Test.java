package ae.erasure;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Gf256Test {
    /** Slow oracle: carry-less multiply, reduced by 0x11D. */
    private static int slowMul(int a, int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            if ((b >> i & 1) != 0) {
                r ^= a << i;
            }
        }
        for (int bit = 15; bit >= 8; bit--) {
            if ((r >> bit & 1) != 0) {
                r ^= 0x11D << (bit - 8);
            }
        }
        return r;
    }

    @Test
    void mulMatchesCarrylessOracleForAllPairs() {
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                assertEquals(slowMul(a, b), Gf256.mul(a, b), a + "*" + b);
            }
        }
    }

    @Test
    void everyNonZeroElementHasInverse() {
        for (int a = 1; a < 256; a++) {
            assertEquals(1, Gf256.mul(a, Gf256.inv(a)), "a=" + a);
            assertEquals(a, Gf256.div(Gf256.mul(a, 77), 77));
        }
    }

    @Test
    void zeroHasNoInverseAndCannotDivide() {
        assertThrows(ArithmeticException.class, () -> Gf256.inv(0));
        assertThrows(ArithmeticException.class, () -> Gf256.div(5, 0));
        assertEquals(0, Gf256.div(0, 5));
    }

    @Test
    void distributiveAndAssociativeOnSample() {
        for (int a = 0; a < 256; a += 7) {
            for (int b = 0; b < 256; b += 5) {
                for (int c = 0; c < 256; c += 11) {
                    assertEquals(Gf256.mul(a, b ^ c), Gf256.mul(a, b) ^ Gf256.mul(a, c));
                    assertEquals(Gf256.mul(Gf256.mul(a, b), c), Gf256.mul(a, Gf256.mul(b, c)));
                }
            }
        }
    }

    @Test
    void generatorTwoHasOrder255() {
        int x = 1;
        for (int i = 1; i < 255; i++) {
            x = Gf256.mul(x, 2);
            assertNotEquals(1, x, "order divides " + i);
        }
        assertEquals(1, Gf256.mul(x, 2));
    }

    @Test
    void powEdgeCases() {
        assertEquals(1, Gf256.pow(0, 0));
        assertEquals(0, Gf256.pow(0, 3));
        assertEquals(1, Gf256.pow(9, 0));
        assertEquals(Gf256.mul(3, Gf256.mul(3, 3)), Gf256.pow(3, 3));
        assertEquals(1, Gf256.pow(200, 255));
    }

    @Test
    void addIsXor() {
        assertEquals(0x55 ^ 0x0F, Gf256.add(0x55, 0x0F));
    }
}
