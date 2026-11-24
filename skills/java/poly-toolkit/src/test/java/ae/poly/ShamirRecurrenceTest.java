package ae.poly;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class ShamirRecurrenceTest {
    private static final long P = 998244353L;

    private static LongSupplier fixed(long... vals) {
        int[] i = {0};
        return () -> vals[i[0]++];
    }

    @Test
    void splitLiteralShares() {
        // f(x) = 1234 + 166x + 94x^2: f(1)=1494, f(2)=1234+332+376=1942, f(3)=1234+498+846=2578
        List<Shamir.Share> s = Shamir.split(1234, 3, 3, fixed(166, 94));
        assertEquals(List.of(new Shamir.Share(1, 1494), new Shamir.Share(2, 1942), new Shamir.Share(3, 2578)), s);
        assertEquals(1234, Shamir.recover(s));
    }

    @Test
    void anyKSharesRecoverSecret() {
        Random r = new Random(10);
        LongSupplier src = () -> r.nextInt((int) P);
        List<Shamir.Share> all = Shamir.split(987654321, 4, 9, src);
        assertEquals(9, all.size());
        for (int a = 0; a < 9; a++)
            for (int b = a + 1; b < 9; b++)
                for (int c = b + 1; c < 9; c++)
                    for (int d = c + 1; d < 9; d++)
                        assertEquals(987654321, Shamir.recover(List.of(all.get(a), all.get(b), all.get(c), all.get(d))));
        assertEquals(987654321, Shamir.recover(all)); // more than k shares
    }

    @Test
    void kMinusOneSharesAreConsistentWithEverySecret() {
        Random r = new Random(11);
        List<Shamir.Share> all = Shamir.split(555, 3, 5, () -> r.nextInt((int) P));
        List<Shamir.Share> two = all.subList(1, 3);
        for (long candidate : new long[] {0, 1, 555, 556, P - 1, 424242}) {
            long[] xs = {0, two.get(0).x(), two.get(1).x()};
            long[] ys = {candidate, two.get(0).y(), two.get(1).y()};
            Poly f = Poly.interpolate(xs, ys);
            assertTrue(f.degree() < 3);
            assertEquals(two.get(0).y(), f.eval(two.get(0).x()));
            assertEquals(candidate, f.eval(0));
        }
        // and a naive recover from k-1 shares does not return the secret (for this seed)
        assertNotEquals(555, Shamir.recover(two));
    }

    @Test
    void thresholdOneMakesEveryShareTheSecret() {
        for (Shamir.Share s : Shamir.split(77, 1, 4, fixed())) assertEquals(77, s.y());
    }

    @Test
    void consistencyDetectsTampering() {
        Random r = new Random(12);
        List<Shamir.Share> all = new ArrayList<>(Shamir.split(31337, 3, 6, () -> r.nextInt((int) P)));
        assertTrue(Shamir.isConsistent(all, 3));
        assertTrue(Shamir.isConsistent(all.subList(0, 3), 3));
        Shamir.Share bad = all.get(4);
        all.set(4, new Shamir.Share(bad.x(), (bad.y() + 1) % P));
        assertFalse(Shamir.isConsistent(all, 3));
        assertTrue(Shamir.isConsistent(all.subList(0, 4), 3));
    }

    @Test
    void consistencyThresholdIsExactlyDegreeBelowK() {
        // shares of f(x) = 1 + x + x^2 + x^3 (degree 3): f(1)=4, f(2)=15, f(3)=40, f(4)=85
        List<Shamir.Share> pts = List.of(new Shamir.Share(1, 4), new Shamir.Share(2, 15),
                new Shamir.Share(3, 40), new Shamir.Share(4, 85));
        assertFalse(Shamir.isConsistent(pts, 3)); // needs degree < 3
        assertTrue(Shamir.isConsistent(pts, 4));
    }

    @Test
    void validatesArguments() {
        LongSupplier s = fixed(1, 1, 1, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> Shamir.split(1, 0, 3, s));
        assertThrows(IllegalArgumentException.class, () -> Shamir.split(1, 4, 3, s));
        assertThrows(IllegalArgumentException.class, () -> Shamir.split(P, 2, 3, s));
        assertThrows(IllegalArgumentException.class, () -> Shamir.split(-1, 2, 3, s));
        assertThrows(NullPointerException.class, () -> Shamir.split(1, 2, 3, null));
        assertThrows(IllegalArgumentException.class, () -> Shamir.recover(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Shamir.recover(List.of(new Shamir.Share(1, 5), new Shamir.Share(1, 6))));
        assertThrows(IllegalArgumentException.class, () -> Shamir.recover(List.of(new Shamir.Share(0, 5))));
        assertThrows(IllegalArgumentException.class, () -> Shamir.isConsistent(List.of(new Shamir.Share(1, 5)), 0));
    }

    @Test
    void splitReducesSuppliedCoefficientsModP() {
        List<Shamir.Share> s = Shamir.split(10, 2, 2, fixed(P + 3)); // slope reduces to 3
        assertEquals(List.of(new Shamir.Share(1, 13), new Shamir.Share(2, 16)), s);
    }

    // ---- recurrences -------------------------------------------------------------------

    private static long[] generate(long[] c, long[] init, int len) {
        long[] s = new long[len];
        System.arraycopy(init, 0, s, 0, init.length);
        for (int i = init.length; i < len; i++) {
            long v = 0;
            for (int j = 1; j <= c.length; j++) v = Mod.add(v, Mod.mul(c[j - 1], s[i - j]));
            s[i] = v;
        }
        return s;
    }

    private static long[][] matMul(long[][] a, long[][] b) {
        long[][] r = new long[2][2];
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                for (int k = 0; k < 2; k++) r[i][j] = Mod.add(r[i][j], Mod.mul(a[i][k], b[k][j]));
        return r;
    }

    private static long fibMatrix(long n) {
        long[][] res = {{1, 0}, {0, 1}};
        long[][] base = {{1, 1}, {1, 0}};
        for (long e = n; e > 0; e >>= 1) {
            if ((e & 1) == 1) res = matMul(res, base);
            base = matMul(base, base);
        }
        return res[0][1]; // F(n)
    }

    @Test
    void berlekampMasseyOnFibonacci() {
        long[] fib = {0, 1, 1, 2, 3, 5, 8, 13, 21, 34};
        assertArrayEquals(new long[] {1, 1}, Recurrence.berlekampMassey(fib));
    }

    @Test
    void berlekampMasseySmallCases() {
        assertArrayEquals(new long[] {}, Recurrence.berlekampMassey(new long[] {0, 0, 0, 0}));
        assertArrayEquals(new long[] {}, Recurrence.berlekampMassey(new long[] {}));
        assertArrayEquals(new long[] {1}, Recurrence.berlekampMassey(new long[] {5, 5, 5, 5}));
        assertArrayEquals(new long[] {2}, Recurrence.berlekampMassey(new long[] {1, 2, 4, 8, 16}));
        // 1,0,0,0: s[1] = c*s[0] forces c = 0, so order 1 with c = [0]
        assertArrayEquals(new long[] {0}, Recurrence.berlekampMassey(new long[] {1, 0, 0, 0}));
    }

    @Test
    void berlekampMasseyPredictsRandomRecurrences() {
        Random r = new Random(13);
        int exact = 0;
        for (int trial = 0; trial < 200; trial++) {
            int order = 1 + r.nextInt(8);
            long[] c = new long[order];
            long[] init = new long[order];
            for (int i = 0; i < order; i++) {
                c[i] = r.nextInt((int) P);
                init[i] = r.nextInt((int) P);
            }
            c[order - 1] = 1 + r.nextInt((int) P - 1); // true order is exactly `order`
            long[] full = generate(c, init, 80);
            long[] found = Recurrence.berlekampMassey(java.util.Arrays.copyOf(full, 2 * order));
            assertTrue(found.length <= order, "order bound");
            long[] regen = generate(found, java.util.Arrays.copyOf(full, found.length), 80);
            assertArrayEquals(full, regen, "trial " + trial);
            if (found.length == order) exact++;
        }
        assertTrue(exact >= 190, "random full-order recurrences are recovered exactly, got " + exact);
    }

    @Test
    void nthTermMatchesDirectGeneration() {
        long[] c = {3, P - 2, 5};
        long[] init = {1, 4, 9};
        long[] full = generate(c, init, 200);
        for (int n = 0; n < 200; n++) assertEquals(full[n], Recurrence.nthTerm(c, init, n), "n=" + n);
    }

    @Test
    void nthTermFibonacciHugeIndex() {
        long[] c = {1, 1};
        long[] init = {0, 1};
        for (long n : new long[] {10, 90, 1_000_000_007L, 1_000_000_000_000_000_000L, (1L << 62) - 1}) {
            assertEquals(fibMatrix(n), Recurrence.nthTerm(c, init, n), "n=" + n);
        }
        assertEquals(55, Recurrence.nthTerm(c, init, 10)); // published F(10)
    }

    @Test
    void nthTermEdgeCases() {
        assertEquals(0, Recurrence.nthTerm(new long[] {}, new long[] {}, 5));
        assertEquals(7, Recurrence.nthTerm(new long[] {1}, new long[] {7}, 1_000_000_000_000L)); // constant
        assertThrows(IllegalArgumentException.class, () -> Recurrence.nthTerm(new long[] {1, 1}, new long[] {1}, 3));
        assertThrows(IllegalArgumentException.class, () -> Recurrence.nthTerm(new long[] {1}, new long[] {1}, -1));
    }

    @Test
    void endToEndBmThenNthTerm() {
        long[] fibSeed = generate(new long[] {1, 1}, new long[] {0, 1}, 12);
        long[] c = Recurrence.berlekampMassey(fibSeed);
        assertEquals(fibMatrix(123456789012345678L),
                Recurrence.nthTerm(c, java.util.Arrays.copyOf(fibSeed, c.length), 123456789012345678L));
    }
}
