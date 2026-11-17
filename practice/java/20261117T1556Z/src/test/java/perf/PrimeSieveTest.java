package perf;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;

class PrimeSieveTest {
    @Test
    void firstPrimesAreLiteral() {
        assertArrayEquals(new int[] {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47},
                PrimeSieve.of(50).primes());
    }

    @Test
    void tinyLimits() {
        assertEquals(0, PrimeSieve.of(0).count());
        assertEquals(0, PrimeSieve.of(1).count());
        assertArrayEquals(new int[] {2}, PrimeSieve.of(2).primes());
        assertArrayEquals(new int[] {2, 3}, PrimeSieve.of(3).primes());
        assertArrayEquals(new int[] {2, 3}, PrimeSieve.of(4).primes());
    }

    @Test
    void negativeLimitRejected() {
        assertThrows(IllegalArgumentException.class, () -> PrimeSieve.of(-1));
    }

    @Test
    void publishedCounts() {
        assertEquals(25, PrimeSieve.of(100).count());
        assertEquals(168, PrimeSieve.of(1000).count());
        assertEquals(9592, PrimeSieve.of(100_000).count());
        assertEquals(78498, PrimeSieve.of(1_000_000).count());
        assertEquals(664579, PrimeSieve.of(10_000_000).count());
    }

    @Test
    void limitBoundaryPrimeIncluded() {
        // 97 is prime and is the limit; 96 and 98 limits must not change the verdicts.
        assertTrue(PrimeSieve.of(97).isPrime(97));
        assertEquals(25, PrimeSieve.of(97).count());
        assertEquals(24, PrimeSieve.of(96).count());
        assertEquals(25, PrimeSieve.of(98).count());
    }

    @Test
    void isPrimeAgreesWithTrialDivision() {
        PrimeSieve s = PrimeSieve.of(20_000);
        for (int n = -5; n <= 20_000; n++) {
            assertEquals(trial(n), s.isPrime(n), "n=" + n);
        }
    }

    @Test
    void isPrimeAboveLimitThrows() {
        PrimeSieve s = PrimeSieve.of(100);
        assertThrows(IllegalArgumentException.class, () -> s.isPrime(101));
    }

    @Test
    void countUpTo() {
        PrimeSieve s = PrimeSieve.of(1000);
        assertEquals(0, s.countUpTo(-3));
        assertEquals(0, s.countUpTo(1));
        assertEquals(1, s.countUpTo(2));
        assertEquals(2, s.countUpTo(3));
        assertEquals(2, s.countUpTo(4));
        assertEquals(25, s.countUpTo(100));
        assertEquals(168, s.countUpTo(1000));
        assertThrows(IllegalArgumentException.class, () -> s.countUpTo(1001));
    }

    @Test
    void countUpToMatchesPrimesArray() {
        PrimeSieve s = PrimeSieve.of(5000);
        int[] ps = s.primes();
        Random r = new Random(7);
        for (int i = 0; i < 300; i++) {
            int n = r.nextInt(5001);
            int expected = 0;
            for (int p : ps) {
                if (p <= n) {
                    expected++;
                }
            }
            assertEquals(expected, s.countUpTo(n), "n=" + n);
        }
    }

    @Test
    void nthPrime() {
        PrimeSieve s = PrimeSieve.of(2_000_000);
        assertEquals(2, s.nth(1));
        assertEquals(3, s.nth(2));
        assertEquals(541, s.nth(100));
        assertEquals(7919, s.nth(1000));
        assertEquals(104729, s.nth(10_000));
        assertEquals(1299709, s.nth(100_000));
        assertEquals(s.primes()[s.count() - 1], s.nth(s.count()));
        assertThrows(IllegalArgumentException.class, () -> s.nth(0));
        assertThrows(IllegalArgumentException.class, () -> s.nth(s.count() + 1));
    }

    @Test
    void nthOnEmptySieveThrows() {
        assertThrows(IllegalArgumentException.class, () -> PrimeSieve.of(1).nth(1));
    }

    @Test
    void primesReturnsFreshArray() {
        PrimeSieve s = PrimeSieve.of(30);
        s.primes()[0] = 99;
        assertEquals(2, s.primes()[0]);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void fiftyMillionIsFast() {
        PrimeSieve s = PrimeSieve.of(50_000_000);
        assertEquals(3_001_134, s.count());
    }

    private static boolean trial(int n) {
        if (n < 2) {
            return false;
        }
        for (int d = 2; (long) d * d <= n; d++) {
            if (n % d == 0) {
                return false;
            }
        }
        return true;
    }
}
