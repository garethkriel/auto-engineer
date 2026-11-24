package ae.poly;

/** Arithmetic in the prime field Z_p with p = 998244353 = 119 * 2^23 + 1. Values are longs in [0, p). */
public final class Mod {
    /** The field modulus. */
    public static final long P = 998244353L;

    private Mod() {}

    /** Reduces any long into [0, p). */
    public static long norm(long a) {
        return Math.floorMod(a, P);
    }

    public static long add(long a, long b) {
        long s = a + b;
        return s >= P ? s - P : s;
    }

    public static long sub(long a, long b) {
        long d = a - b;
        return d < 0 ? d + P : d;
    }

    /** Product of two reduced values; the product is below 2^60 so it cannot overflow. */
    public static long mul(long a, long b) {
        return a * b % P;
    }

    /** {@code a^e mod p} for e >= 0. */
    public static long pow(long a, long e) {
        if (e < 0) {
            throw new IllegalArgumentException("negative exponent");
        }
        long result = 1;
        long base = a;
        for (long k = e; k > 0; k >>= 1) {
            if ((k & 1) == 1) {
                result = mul(result, base);
            }
            base = mul(base, base);
        }
        return result;
    }

    /** Multiplicative inverse; throws for zero. */
    public static long inv(long a) {
        if (a == 0) {
            throw new ArithmeticException("inverse of zero");
        }
        return pow(a, P - 2);
    }
}
