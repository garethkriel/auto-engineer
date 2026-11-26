package ae.pk;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PrimesTest {
    @Test
    void agreesWithSieveBelow200000() {
        int limit = 200_000;
        boolean[] comp = new boolean[limit];
        comp[0] = true;
        comp[1] = true;
        for (int i = 2; (long) i * i < limit; i++) {
            if (!comp[i]) for (int j = i * i; j < limit; j += i) comp[j] = true;
        }
        Random rnd = new Random(1);
        int primes = 0;
        for (int n = 0; n < limit; n++) {
            assertEquals(!comp[n], Primes.isProbablePrime(BigInteger.valueOf(n), rnd), "n=" + n);
            if (!comp[n]) primes++;
        }
        assertEquals(17984, primes, "pi(200000)");
        assertFalse(Primes.isProbablePrime(BigInteger.valueOf(-7), rnd), "negative");
    }

    @Test
    void rejectsStrongPseudoprimesAndCarmichaelNumbers() {
        Random rnd = new Random(2);
        // strong pseudoprimes to base 2, Carmichael numbers, and the smallest spsp to bases 2,3,5,7
        long[] composites = {2047, 3277, 4033, 4681, 8321, 561, 1105, 41041, 825265, 321197185L,
            3215031751L, 3825123056546413051L};
        for (long c : composites) {
            assertFalse(Primes.isProbablePrime(BigInteger.valueOf(c), rnd), "composite " + c);
        }
        // 3317044064679887385961981 is a strong pseudoprime to the first 12 prime bases (3.3e24 bound is sharp)
        BigInteger sharp = new BigInteger("3317044064679887385961981");
        assertFalse(Primes.isProbablePrime(sharp, new Random(5)), "needs the random bases");
    }

    @Test
    void largePrimesAndComposites() {
        Random rnd = new Random(3);
        BigInteger m127 = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
        BigInteger m521 = BigInteger.ONE.shiftLeft(521).subtract(BigInteger.ONE);
        assertTrue(Primes.isProbablePrime(m127, rnd));
        assertTrue(Primes.isProbablePrime(m521, rnd));
        assertFalse(Primes.isProbablePrime(BigInteger.ONE.shiftLeft(67).subtract(BigInteger.ONE), rnd), "M67 composite");
        assertFalse(Primes.isProbablePrime(m127.multiply(m127), rnd), "square of a prime");
        assertFalse(Primes.isProbablePrime(m127.multiply(m521), rnd));
        assertTrue(Primes.isProbablePrime(Curve.P256.p(), rnd));
        assertTrue(Primes.isProbablePrime(Curve.P256.n(), rnd));
        assertTrue(Primes.isProbablePrime(Curve.SECP256K1.n(), rnd));
    }

    @Test
    void randomPrimeHasExactBitLengthTopBitsAndIsPrime() {
        Random rnd = new Random(9);
        for (int bits : new int[] {16, 64, 256}) {
            for (int i = 0; i < 5; i++) {
                BigInteger p = Primes.randomPrime(bits, rnd);
                assertEquals(bits, p.bitLength());
                assertTrue(p.testBit(bits - 2), "top two bits set so products have full length");
                assertTrue(p.isProbablePrime(40), "JDK oracle agrees " + p);
            }
        }
        assertEquals(Primes.randomPrime(128, new Random(4)), Primes.randomPrime(128, new Random(4)), "seeded");
        assertThrows(IllegalArgumentException.class, () -> Primes.randomPrime(3, new Random(1)));
    }
}
