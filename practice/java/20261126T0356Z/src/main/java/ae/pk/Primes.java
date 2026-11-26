package ae.pk;

import java.math.BigInteger;
import java.util.Random;

/** Miller-Rabin primality testing and random prime generation. */
public final class Primes {
    private static final int[] BASES = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37};
    /** The first 12 prime bases are deterministic below 3.18e23 (< 2^78); above, random bases are added. */
    private static final int DETERMINISTIC_BITS = 78;
    private static final int RANDOM_ROUNDS = 16;

    private Primes() { }

    /** Miller-Rabin test; exact for n < 2^78, error below 4^-16 otherwise. */
    public static boolean isProbablePrime(BigInteger n, Random rnd) {
        if (n.compareTo(BigInteger.TWO) < 0) {
            return false;
        }
        for (int b : BASES) {
            BigInteger bb = BigInteger.valueOf(b);
            if (n.equals(bb)) {
                return true;
            }
            if (n.mod(bb).signum() == 0) {
                return false;
            }
        }
        BigInteger nm1 = n.subtract(BigInteger.ONE);
        int s = nm1.getLowestSetBit();
        BigInteger d = nm1.shiftRight(s);
        for (int b : BASES) {
            if (!passes(n, nm1, d, s, BigInteger.valueOf(b))) {
                return false;
            }
        }
        if (n.bitLength() > DETERMINISTIC_BITS) {
            for (int i = 0; i < RANDOM_ROUNDS; i++) {
                BigInteger a;
                do {
                    a = new BigInteger(n.bitLength(), rnd);
                } while (a.compareTo(BigInteger.TWO) < 0 || a.compareTo(nm1) >= 0);
                if (!passes(n, nm1, d, s, a)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean passes(BigInteger n, BigInteger nm1, BigInteger d, int s, BigInteger a) {
        BigInteger x = a.modPow(d, n);
        if (x.equals(BigInteger.ONE) || x.equals(nm1)) {
            return true;
        }
        for (int i = 1; i < s; i++) {
            x = x.multiply(x).mod(n);
            if (x.equals(nm1)) {
                return true;
            }
        }
        return false;
    }

    /** A random prime of exactly {@code bits} bits whose top two bits are set (so products have full length). */
    public static BigInteger randomPrime(int bits, Random rnd) {
        if (bits < 4) {
            throw new IllegalArgumentException("bits must be at least 4");
        }
        while (true) {
            BigInteger c = new BigInteger(bits, rnd).setBit(bits - 1).setBit(bits - 2).setBit(0);
            if (isProbablePrime(c, rnd)) {
                return c;
            }
        }
    }
}
