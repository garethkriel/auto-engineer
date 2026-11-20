package ae.erasure;

/** Arithmetic in GF(2^8) with reducing polynomial x^8+x^4+x^3+x^2+1 (0x11D) and generator 2. */
public final class Gf256 {
    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) {
                x ^= 0x11D;
            }
        }
        for (int i = 255; i < 512; i++) {
            EXP[i] = EXP[i - 255];
        }
    }

    private Gf256() {
    }

    /** Field addition (xor). */
    public static int add(int a, int b) {
        return (a ^ b) & 0xFF;
    }

    /** Field multiplication. */
    public static int mul(int a, int b) {
        a &= 0xFF;
        b &= 0xFF;
        if (a == 0 || b == 0) {
            return 0;
        }
        return EXP[LOG[a] + LOG[b]];
    }

    /** Multiplicative inverse; throws for zero. */
    public static int inv(int a) {
        a &= 0xFF;
        if (a == 0) {
            throw new ArithmeticException("zero has no inverse in GF(256)");
        }
        return EXP[255 - LOG[a]];
    }

    /** Field division {@code a / b}; throws if {@code b} is zero. */
    public static int div(int a, int b) {
        return mul(a, inv(b));
    }

    /** {@code a^e} for e >= 0, with {@code 0^0 = 1}. */
    public static int pow(int a, int e) {
        if (e < 0) {
            throw new IllegalArgumentException("negative exponent");
        }
        a &= 0xFF;
        if (e == 0) {
            return 1;
        }
        if (a == 0) {
            return 0;
        }
        return EXP[(int) ((long) LOG[a] * e % 255)];
    }
}
