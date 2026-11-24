package ae.poly;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ModNttTest {
    private static final long P = 998244353L;
    private static final BigInteger BP = BigInteger.valueOf(P);

    @Test
    void mulMatchesBigIntegerNearModulus() {
        long[] vals = {0, 1, 2, P - 1, P - 2, 123456789, 998244352, 500000000};
        for (long a : vals) {
            for (long b : vals) {
                long want = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).mod(BP).longValue();
                assertEquals(want, Mod.mul(a, b), a + "*" + b);
            }
        }
    }

    @Test
    void addSubNormWrapAround() {
        assertEquals(0, Mod.add(P - 1, 1));
        assertEquals(P - 1, Mod.sub(0, 1));
        assertEquals(P - 7, Mod.norm(-7));
        assertEquals(3, Mod.norm(3 * P + 3));
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE).mod(BP).longValue(), Mod.norm(Long.MIN_VALUE));
    }

    @Test
    void powAndInverse() {
        assertEquals(1, Mod.pow(5, 0));
        assertEquals(8, Mod.pow(2, 3));
        assertEquals(1, Mod.pow(3, P - 1));
        Random rnd = new Random(1);
        for (int i = 0; i < 200; i++) {
            long a = 1 + rnd.nextInt((int) (P - 1));
            assertEquals(1, Mod.mul(a, Mod.inv(a)));
        }
        assertEquals(499122177L, Mod.inv(2)); // (P+1)/2
    }

    @Test
    void inverseOfZeroThrows() {
        assertThrows(ArithmeticException.class, () -> Mod.inv(0));
    }

    @Test
    void transformOfDeltaIsAllOnes() {
        long[] a = {1, 0, 0, 0};
        Ntt.transform(a, false);
        assertArrayEquals(new long[] {1, 1, 1, 1}, a);
    }

    @Test
    void transformMatchesNaiveDft() {
        Random rnd = new Random(2);
        for (int n : new int[] {1, 2, 4, 8, 32}) {
            long[] a = new long[n];
            for (int i = 0; i < n; i++) a[i] = rnd.nextInt((int) P);
            long w = Mod.pow(3, (P - 1) / n);
            long[] want = new long[n];
            for (int k = 0; k < n; k++) {
                long s = 0;
                for (int j = 0; j < n; j++) s = Mod.add(s, Mod.mul(a[j], Mod.pow(w, (long) j * k)));
                want[k] = s;
            }
            long[] got = a.clone();
            Ntt.transform(got, false);
            assertArrayEquals(want, got, "n=" + n);
        }
    }

    @Test
    void roundTripRestoresInput() {
        Random rnd = new Random(3);
        for (int n : new int[] {1, 2, 16, 1024}) {
            long[] a = new long[n];
            for (int i = 0; i < n; i++) a[i] = rnd.nextInt((int) P);
            long[] b = a.clone();
            Ntt.transform(b, false);
            Ntt.transform(b, true);
            assertArrayEquals(a, b);
        }
    }

    @Test
    void rejectsNonPowerOfTwoLengths() {
        assertThrows(IllegalArgumentException.class, () -> Ntt.transform(new long[3], false));
        assertThrows(IllegalArgumentException.class, () -> Ntt.transform(new long[0], false));
        assertThrows(IllegalArgumentException.class, () -> Ntt.transform(new long[6], true));
    }
}
