package ae.succinct;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BitVectorTest {

    static boolean[] bits(String s) {
        boolean[] b = new boolean[s.length()];
        for (int i = 0; i < b.length; i++) b[i] = s.charAt(i) == '1';
        return b;
    }

    // positions:  0 1 2 3 4 5 6 7 8 9
    // bits:       1 0 1 1 0 0 1 1 1 0   ones at 0,2,3,6,7,8   zeros at 1,4,5,9
    @Test
    void literalRankSelect() {
        BitVector v = BitVector.of(bits("1011001110"));
        assertEquals(10, v.size());
        assertEquals(6, v.ones());
        int[] prefix = {0, 1, 1, 2, 3, 3, 3, 4, 5, 6, 6};
        for (int i = 0; i <= 10; i++) {
            assertEquals(prefix[i], v.rank1(i), "rank1(" + i + ")");
            assertEquals(i - prefix[i], v.rank0(i), "rank0(" + i + ")");
        }
        int[] ones = {0, 2, 3, 6, 7, 8};
        for (int k = 0; k < ones.length; k++) assertEquals(ones[k], v.select1(k));
        int[] zeros = {1, 4, 5, 9};
        for (int k = 0; k < zeros.length; k++) assertEquals(zeros[k], v.select0(k));
        assertTrue(v.get(0));
        assertFalse(v.get(1));
        v.checkInvariants();
    }

    @Test
    void outOfRangeArgumentsThrow() {
        BitVector v = BitVector.of(bits("1011001110"));
        assertThrows(IndexOutOfBoundsException.class, () -> v.rank1(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> v.rank1(11));
        assertThrows(IndexOutOfBoundsException.class, () -> v.rank0(11));
        assertThrows(IndexOutOfBoundsException.class, () -> v.get(10));
        assertThrows(IndexOutOfBoundsException.class, () -> v.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> v.select1(6));
        assertThrows(IndexOutOfBoundsException.class, () -> v.select1(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> v.select0(4));
    }

    @Test
    void emptyVector() {
        BitVector v = BitVector.of(new boolean[0]);
        assertEquals(0, v.size());
        assertEquals(0, v.rank1(0));
        assertEquals(0, v.rank0(0));
        assertThrows(IndexOutOfBoundsException.class, () -> v.select1(0));
        assertThrows(IndexOutOfBoundsException.class, () -> v.select0(0));
        v.checkInvariants();
    }

    @Test
    void allOnesAndAllZerosAtBoundarySizes() {
        for (int n : new int[] {1, 63, 64, 65, 511, 512, 513, 1024, 1025}) {
            boolean[] ones = new boolean[n];
            java.util.Arrays.fill(ones, true);
            BitVector a = BitVector.of(ones);
            BitVector z = BitVector.of(new boolean[n]);
            assertEquals(n, a.ones(), "n=" + n);
            assertEquals(0, z.ones(), "n=" + n);
            for (int k = 0; k < n; k++) {
                assertEquals(k, a.select1(k), "n=" + n + " k=" + k);
                assertEquals(k, z.select0(k), "n=" + n + " k=" + k);
            }
            assertEquals(n, a.rank1(n));
            assertEquals(n, z.rank0(n));
            assertThrows(IndexOutOfBoundsException.class, () -> a.select0(0));
            assertThrows(IndexOutOfBoundsException.class, () -> z.select1(0));
            a.checkInvariants();
            z.checkInvariants();
        }
    }

    @Test
    void singleOneAtEveryBoundary() {
        // the lone one sits at the first/last bit of a word and of a superblock
        int n = 1100;
        for (int p : new int[] {0, 1, 63, 64, 65, 511, 512, 513, 1023, 1024, n - 1}) {
            boolean[] b = new boolean[n];
            b[p] = true;
            BitVector v = BitVector.of(b);
            assertEquals(p, v.select1(0), "p=" + p);
            assertEquals(0, v.rank1(p));
            assertEquals(1, v.rank1(p + 1));
            // select0 skips over the single one
            // zeros are every position except p: select0(j) = j before p, j+1 from p on
            assertEquals(p == 0 ? 1 : 0, v.select0(0));
            if (p < n - 1) assertEquals(p + 1, v.select0(p));
            v.checkInvariants();
        }
    }

    @Test
    void randomDifferentialAgainstPrefixTables() {
        Random rnd = new Random(20261125L);
        double[] density = {0.0, 0.01, 0.1, 0.5, 0.9, 0.99, 1.0};
        for (int round = 0; round < 140; round++) {
            int n = round < 20 ? round : rnd.nextInt(5000);
            double d = density[round % density.length];
            boolean[] b = new boolean[n];
            for (int i = 0; i < n; i++) b[i] = rnd.nextDouble() < d;
            BitVector v = BitVector.of(b);
            int[] pre = new int[n + 1];
            for (int i = 0; i < n; i++) pre[i + 1] = pre[i] + (b[i] ? 1 : 0);
            assertEquals(pre[n], v.ones());
            int one = 0, zero = 0;
            for (int i = 0; i <= n; i++) {
                assertEquals(pre[i], v.rank1(i), "n=" + n + " i=" + i);
                assertEquals(i - pre[i], v.rank0(i));
            }
            for (int i = 0; i < n; i++) {
                assertEquals(b[i], v.get(i));
                if (b[i]) assertEquals(i, v.select1(one++), "n=" + n + " select1");
                else assertEquals(i, v.select0(zero++), "n=" + n + " select0");
            }
            v.checkInvariants();
        }
    }

    @Test
    void rankSelectAreInverse() {
        Random rnd = new Random(7);
        boolean[] b = new boolean[3000];
        for (int i = 0; i < b.length; i++) b[i] = rnd.nextInt(3) == 0;
        BitVector v = BitVector.of(b);
        for (int k = 0; k < v.ones(); k++) assertEquals(k, v.rank1(v.select1(k)));
        for (int k = 0; k < v.size() - v.ones(); k++) assertEquals(k, v.rank0(v.select0(k)));
    }

    @Test
    void builderSetAndRangeChecks() {
        BitVector.Builder bl = new BitVector.Builder(130);
        bl.set(0).set(129).set(64).set(64);
        BitVector v = bl.build();
        assertEquals(3, v.ones());
        assertEquals(64, v.select1(1));
        assertThrows(IndexOutOfBoundsException.class, () -> bl.set(130));
        assertThrows(IndexOutOfBoundsException.class, () -> bl.set(-1));
        assertThrows(IllegalArgumentException.class, () -> new BitVector.Builder(-1));
        // the built vector does not change when the builder is reused
        bl.set(5);
        assertEquals(3, v.ones());
        assertFalse(v.get(5));
    }

    @Test
    void directoryOverheadIsSmall() {
        BitVector v = BitVector.of(new boolean[100_000]);
        // one int per 512 bits (+1), i.e. well under 7% of the payload
        assertTrue(v.directoryBits() <= v.size() / 512 * 32 + 64, "directory bits " + v.directoryBits());
        assertTrue(v.directoryBits() * 14 < v.size() + 64);
    }

    @Test
    void invariantCheckerCatchesEachCorruption() {
        long[] w = {0b1011L, 0L, 0L};
        // correct directory for 130 bits: one superblock starting at 0
        BitVector good = BitVector.unchecked(w.clone(), 130, new int[] {0, 3});
        good.checkInvariants();

        IllegalStateException wrongCount = assertThrows(IllegalStateException.class,
                () -> BitVector.unchecked(w.clone(), 130, new int[] {0, 4}).checkInvariants());
        assertTrue(wrongCount.getMessage().startsWith("bitvector: total"), wrongCount.getMessage());

        IllegalStateException wrongBlock = assertThrows(IllegalStateException.class,
                () -> BitVector.unchecked(new long[] {-1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, 1L, 0L},
                        600, new int[] {0, 511, 513}).checkInvariants());
        assertTrue(wrongBlock.getMessage().startsWith("bitvector: block"), wrongBlock.getMessage());

        // padding bit set beyond n (bit 130 lives in word 2)
        IllegalStateException padding = assertThrows(IllegalStateException.class,
                () -> BitVector.unchecked(new long[] {0b1011L, 0L, 1L << 5}, 130, new int[] {0, 4}).checkInvariants());
        assertTrue(padding.getMessage().startsWith("bitvector: padding"), padding.getMessage());

        IllegalStateException shortDir = assertThrows(IllegalStateException.class,
                () -> BitVector.unchecked(w.clone(), 130, new int[] {0}).checkInvariants());
        assertTrue(shortDir.getMessage().startsWith("bitvector: directory length"), shortDir.getMessage());
    }
}
