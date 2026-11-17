package perf;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class NumberTheoryTest {
    @Test
    void gcdLiterals() {
        assertEquals(6, NumberTheory.gcd(12, 18));
        assertEquals(6, NumberTheory.gcd(-12, 18));
        assertEquals(6, NumberTheory.gcd(12, -18));
        assertEquals(5, NumberTheory.gcd(0, 5));
        assertEquals(5, NumberTheory.gcd(5, 0));
        assertEquals(0, NumberTheory.gcd(0, 0));
        assertEquals(1, NumberTheory.gcd(17, 13));
    }

    @Test
    void gcdExtremes() {
        assertEquals(1, NumberTheory.gcd(Long.MIN_VALUE, 3));
        assertEquals(1L << 62, NumberTheory.gcd(Long.MIN_VALUE, 1L << 62));
        assertEquals(Long.MAX_VALUE, NumberTheory.gcd(Long.MAX_VALUE, 0));
        assertThrows(ArithmeticException.class, () -> NumberTheory.gcd(Long.MIN_VALUE, 0));
        assertThrows(ArithmeticException.class, () -> NumberTheory.gcd(0, Long.MIN_VALUE));
        assertThrows(ArithmeticException.class, () -> NumberTheory.gcd(Long.MIN_VALUE, Long.MIN_VALUE));
    }

    @Test
    void gcdMatchesBigInteger() {
        Random r = new Random(11);
        for (int i = 0; i < 2000; i++) {
            long a = r.nextLong();
            long b = r.nextLong() >> r.nextInt(40);
            if (a == Long.MIN_VALUE || b == Long.MIN_VALUE) {
                continue;
            }
            assertEquals(BigInteger.valueOf(a).gcd(BigInteger.valueOf(b)).longValueExact(),
                    NumberTheory.gcd(a, b));
        }
    }

    @Test
    void modPowLiterals() {
        assertEquals(24, NumberTheory.modPow(2, 10, 1000));
        assertEquals(1, NumberTheory.modPow(5, 0, 7));
        assertEquals(0, NumberTheory.modPow(5, 3, 1));
        assertEquals(0, NumberTheory.modPow(0, 5, 7));
        assertEquals(1, NumberTheory.modPow(0, 0, 7));
        assertEquals(6, NumberTheory.modPow(-2, 3, 7)); // -8 mod 7 = 6
        assertEquals(4, NumberTheory.modPow(-3, 2, 5));
    }

    @Test
    void modPowArgumentChecks() {
        assertThrows(IllegalArgumentException.class, () -> NumberTheory.modPow(2, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> NumberTheory.modPow(2, 3, -5));
        assertThrows(IllegalArgumentException.class, () -> NumberTheory.modPow(2, -1, 5));
    }

    @Test
    void modPowHugeModulusMatchesBigInteger() {
        Random r = new Random(3);
        for (int i = 0; i < 300; i++) {
            long mod = 1 + (r.nextLong() >>> 1);
            long base = r.nextLong();
            long exp = r.nextLong() >>> 1;
            BigInteger expected = BigInteger.valueOf(base)
                    .modPow(BigInteger.valueOf(exp), BigInteger.valueOf(mod));
            assertEquals(expected.longValueExact(), NumberTheory.modPow(base, exp, mod),
                    base + "^" + exp + " mod " + mod);
        }
        long m = Long.MAX_VALUE;
        assertEquals(m - 1, NumberTheory.modPow(m - 1, 1, m));
        assertEquals(m - 1, NumberTheory.modPow(-1, 1, m));
    }

    @Test
    void isPrimeSmallAgainstSieve() {
        PrimeSieve s = PrimeSieve.of(100_000);
        for (int n = -3; n <= 100_000; n++) {
            assertEquals(s.isPrime(Math.max(n, 0)), NumberTheory.isPrime(n), "n=" + n);
        }
    }

    @Test
    void isPrimeKnownBigValues() {
        assertTrue(NumberTheory.isPrime(Long.MAX_VALUE - 24));       // 2^63-25 is the largest long prime
        assertFalse(NumberTheory.isPrime(Long.MAX_VALUE));            // 7^2 * 73 * ...
        assertTrue(NumberTheory.isPrime(1_000_000_007L));
        assertTrue(NumberTheory.isPrime(998_244_353L));
        assertTrue(NumberTheory.isPrime((1L << 61) - 1));             // Mersenne prime
        assertFalse(NumberTheory.isPrime((1L << 62) - 1));
        // Carmichael numbers and strong pseudoprimes to base 2
        assertFalse(NumberTheory.isPrime(561));
        assertFalse(NumberTheory.isPrime(3_215_031_751L));            // spsp to bases 2,3,5,7
        assertFalse(NumberTheory.isPrime(3_825_123_056_546_413_051L)); // spsp to first 9 primes
        assertFalse(NumberTheory.isPrime(Long.MIN_VALUE));
    }

    @Test
    void isPrimeMatchesBigIntegerRandom() {
        Random r = new Random(5);
        for (int i = 0; i < 400; i++) {
            long n = r.nextLong() >>> r.nextInt(63);
            assertEquals(BigInteger.valueOf(n).isProbablePrime(60), NumberTheory.isPrime(n), "n=" + n);
        }
    }

    @Test
    void factorizeLiterals() {
        assertEquals(sorted(2, 1), NumberTheory.factorize(2));
        assertEquals(sorted(2, 2, 3, 1, 5, 1), NumberTheory.factorize(60));
        assertEquals(sorted(2, 10), NumberTheory.factorize(1024));
        assertEquals(sorted(1_000_000_007L, 1), NumberTheory.factorize(1_000_000_007L));
        assertEquals(sorted(2, 1, 3, 1, 5, 1, 7, 1, 11, 1, 13, 1), NumberTheory.factorize(30030));
        assertEquals(sorted(7, 2, 73, 1, 127, 1, 337, 1, 92737, 1, 649657, 1),
                NumberTheory.factorize(Long.MAX_VALUE));
    }

    @Test
    void factorizeRejectsSmall() {
        assertThrows(IllegalArgumentException.class, () -> NumberTheory.factorize(1));
        assertThrows(IllegalArgumentException.class, () -> NumberTheory.factorize(0));
        assertThrows(IllegalArgumentException.class, () -> NumberTheory.factorize(-12));
    }

    @Test
    void factorizeReproducesAndOnlyPrimes() {
        Random r = new Random(9);
        for (int i = 0; i < 300; i++) {
            long n = 2 + (r.nextLong() >>> (1 + r.nextInt(60)));
            SortedMap<Long, Integer> f = NumberTheory.factorize(n);
            BigInteger product = BigInteger.ONE;
            long prev = 0;
            for (Map.Entry<Long, Integer> e : f.entrySet()) {
                assertTrue(e.getKey() > prev, "ascending");
                prev = e.getKey();
                assertTrue(BigInteger.valueOf(e.getKey()).isProbablePrime(40), "prime " + e.getKey());
                assertTrue(e.getValue() >= 1);
                product = product.multiply(BigInteger.valueOf(e.getKey()).pow(e.getValue()));
            }
            assertEquals(BigInteger.valueOf(n), product, "n=" + n);
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void largePrimeNear2Pow62IsFast() {
        long p = (1L << 62) - 57; // largest prime below 2^62
        assertTrue(NumberTheory.isPrime(p));
        assertEquals(sorted(p, 1), NumberTheory.factorize(p));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void semiprimesOfTwoBigPrimesAreFast() {
        long p = 2_147_483_647L;      // 2^31-1
        long q = 2_147_483_629L;      // prime below it
        assertTrue(NumberTheory.isPrime(q));
        assertEquals(sorted(q, 1, p, 1), NumberTheory.factorize(p * q));
        long a = 1_000_000_007L;
        long b = 1_000_000_009L;
        assertEquals(sorted(a, 1, b, 1), NumberTheory.factorize(a * b));
        assertEquals(sorted(a, 2), NumberTheory.factorize(a * a));
    }

    /** Builds a map from alternating prime, exponent pairs (or a single prime, exponent). */
    private static SortedMap<Long, Integer> sorted(long... pairs) {
        SortedMap<Long, Integer> m = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], (int) pairs[i + 1]);
        }
        return m;
    }
}
