package perf;

import java.math.BigInteger;
import java.util.SortedMap;
import java.util.TreeMap;

/** 64-bit number theory: gcd, modular exponentiation, primality and factorisation. */
public final class NumberTheory {
    private static final long[] SMALL_PRIMES = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37};
    private static final int TRIAL_BOUND = 1 << 12;

    private NumberTheory() {
    }

    /** Non-negative gcd; throws if the result is not representable (2^63). */
    public static long gcd(long a, long b) {
        long x = a;
        long y = b;
        // Work with non-positive values so Long.MIN_VALUE needs no negation up front.
        x = x > 0 ? -x : x;
        y = y > 0 ? -y : y;
        while (y != 0) {
            long t = x % y;
            x = y;
            y = t;
        }
        if (x == Long.MIN_VALUE) {
            throw new ArithmeticException("gcd is 2^63, not representable");
        }
        return -x;
    }

    /** {@code base^exp mod mod} in [0, mod), for any {@code mod >= 1}. */
    public static long modPow(long base, long exp, long mod) {
        if (mod < 1) {
            throw new IllegalArgumentException("mod must be >= 1: " + mod);
        }
        if (exp < 0) {
            throw new IllegalArgumentException("exp must be >= 0: " + exp);
        }
        long result = 1 % mod;
        long b = Math.floorMod(base, mod);
        long e = exp;
        while (e > 0) {
            if ((e & 1) == 1) {
                result = mulMod(result, b, mod);
            }
            b = mulMod(b, b, mod);
            e >>= 1;
        }
        return result;
    }

    /** (a * b) mod m for 0 <= a, b < m, without overflow. */
    static long mulMod(long a, long b, long m) {
        long hi = Math.multiplyHigh(a, b);
        long lo = a * b;
        if (hi == 0 && lo >= 0) {
            return lo % m;
        }
        return new BigInteger(Long.toUnsignedString(lo)).add(BigInteger.valueOf(hi).shiftLeft(64))
                .mod(BigInteger.valueOf(m)).longValue();
    }

    /** Deterministic Miller-Rabin; correct for every {@code long}. */
    public static boolean isPrime(long n) {
        if (n < 2) {
            return false;
        }
        for (long p : SMALL_PRIMES) {
            if (n == p) {
                return true;
            }
            if (n % p == 0) {
                return false;
            }
        }
        long d = n - 1;
        int s = Long.numberOfTrailingZeros(d);
        d >>= s;
        for (long a : SMALL_PRIMES) {
            long x = modPow(a, d, n);
            if (x == 1 || x == n - 1) {
                continue;
            }
            boolean witness = true;
            for (int r = 1; r < s; r++) {
                x = mulMod(x, x, n);
                if (x == n - 1) {
                    witness = false;
                    break;
                }
            }
            if (witness) {
                return false;
            }
        }
        return true;
    }

    /** Prime factorisation of {@code n >= 2}: prime to exponent, ascending. */
    public static SortedMap<Long, Integer> factorize(long n) {
        if (n < 2) {
            throw new IllegalArgumentException("n must be >= 2: " + n);
        }
        SortedMap<Long, Integer> out = new TreeMap<>();
        long rest = n;
        for (long d = 2; d < TRIAL_BOUND && d * d <= rest; d += d == 2 ? 1 : 2) {
            while (rest % d == 0) {
                out.merge(d, 1, Integer::sum);
                rest /= d;
            }
        }
        split(rest, out);
        return out;
    }

    private static void split(long n, SortedMap<Long, Integer> out) {
        if (n == 1) {
            return;
        }
        if (isPrime(n)) {
            out.merge(n, 1, Integer::sum);
            return;
        }
        long d = rho(n);
        split(d, out);
        split(n / d, out);
    }

    /** Returns a non-trivial divisor of composite {@code n} (Pollard rho, Brent-free Floyd). */
    private static long rho(long n) {
        if ((n & 1) == 0) {
            return 2;
        }
        for (long c = 1; ; c++) {
            long x = 2;
            long y = 2;
            long d = 1;
            while (d == 1) {
                x = step(x, c, n);
                y = step(step(y, c, n), c, n);
                d = gcd(Math.abs(x - y), n);
            }
            if (d != n) {
                return d;
            }
        }
    }

    private static long step(long x, long c, long n) {
        long v = mulMod(x, x, n) + c;
        return v >= n || v < 0 ? v - n : v;
    }
}
