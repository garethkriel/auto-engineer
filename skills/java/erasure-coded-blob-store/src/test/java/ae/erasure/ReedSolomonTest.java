package ae.erasure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ReedSolomonTest {
    private static byte[][] randomData(Random rnd, int k, int len) {
        byte[][] d = new byte[k][len];
        for (byte[] s : d) {
            rnd.nextBytes(s);
        }
        return d;
    }

    private static byte[][] all(byte[][] data, byte[][] parity) {
        byte[][] n = new byte[data.length + parity.length][];
        for (int i = 0; i < data.length; i++) {
            n[i] = data[i].clone();
        }
        for (int i = 0; i < parity.length; i++) {
            n[data.length + i] = parity[i].clone();
        }
        return n;
    }

    /** Every subset of erased shards of size <= m must be recoverable. */
    private static void checkEveryErasurePattern(int k, int m, int len, long seed) {
        ReedSolomon rs = new ReedSolomon(k, m);
        byte[][] data = randomData(new Random(seed), k, len);
        byte[][] full = all(data, rs.encode(data));
        int n = k + m;
        int patterns = 0;
        for (int mask = 0; mask < 1 << n; mask++) {
            if (Integer.bitCount(mask) > m) {
                continue;
            }
            patterns++;
            byte[][] work = new byte[n][];
            boolean[] present = new boolean[n];
            for (int i = 0; i < n; i++) {
                present[i] = (mask >> i & 1) == 0;
                work[i] = present[i] ? full[i].clone() : (i % 2 == 0 ? new byte[len] : null);
            }
            rs.reconstruct(work, present);
            for (int i = 0; i < n; i++) {
                assertArrayEquals(full[i], work[i], "k=" + k + " m=" + m + " mask=" + mask + " shard " + i);
            }
        }
        assertTrue(patterns > 1);
    }

    @Test
    void mdsPropertyExhaustiveForSmallCodes() {
        checkEveryErasurePattern(1, 1, 5, 1);
        checkEveryErasurePattern(2, 1, 8, 2);
        checkEveryErasurePattern(3, 2, 7, 3);
        checkEveryErasurePattern(4, 4, 6, 4);
        checkEveryErasurePattern(5, 3, 9, 5);
        checkEveryErasurePattern(1, 6, 3, 6);
    }

    @Test
    void systematicCodeKeepsDataShardsUntouched() {
        ReedSolomon rs = new ReedSolomon(3, 2);
        byte[][] data = randomData(new Random(9), 3, 10);
        byte[][] copy = new byte[3][];
        for (int i = 0; i < 3; i++) {
            copy[i] = data[i].clone();
        }
        rs.encode(data);
        assertArrayEquals(copy, data);
    }

    @Test
    void singleParityIsPlainXorFree() {
        // First Cauchy row is inv(k xor j); for k=1,m=1 it is inv(1)=1, so parity == data.
        ReedSolomon rs = new ReedSolomon(1, 1);
        byte[][] data = {{1, 2, 3, (byte) 250}};
        assertArrayEquals(data[0], rs.encode(data)[0]);
    }

    @Test
    void knownParityVectorForTwoPlusOne() {
        // k=2,m=1: C[0][j] = inv(2 xor j): j=0 -> inv(2)=142, j=1 -> inv(3)=244.
        assertEquals(142, Gf256.inv(2));
        assertEquals(244, Gf256.inv(3));
        ReedSolomon rs = new ReedSolomon(2, 1);
        byte[][] parity = rs.encode(new byte[][] {{1}, {1}});
        assertEquals(142 ^ 244, parity[0][0] & 0xFF);
        parity = rs.encode(new byte[][] {{1}, {0}});
        assertEquals(142, parity[0][0] & 0xFF);
        parity = rs.encode(new byte[][] {{0}, {7}});
        assertEquals(Gf256.mul(244, 7), parity[0][0] & 0xFF);
    }

    @Test
    void largeCodeRandomErasures() {
        ReedSolomon rs = new ReedSolomon(20, 10);
        Random rnd = new Random(77);
        for (int round = 0; round < 25; round++) {
            byte[][] data = randomData(rnd, 20, 64);
            byte[][] full = all(data, rs.encode(data));
            boolean[] present = new boolean[30];
            Arrays.fill(present, true);
            int lost = rnd.nextInt(11);
            byte[][] work = new byte[30][];
            for (int i = 0; i < 30; i++) {
                work[i] = full[i].clone();
            }
            for (int l = 0; l < lost; ) {
                int idx = rnd.nextInt(30);
                if (present[idx]) {
                    present[idx] = false;
                    Arrays.fill(work[idx], (byte) 0xAA);
                    l++;
                }
            }
            rs.reconstruct(work, present);
            assertArrayEquals(full, work, "round " + round + " lost " + lost);
        }
    }

    @Test
    void maximumGeometry256Shards() {
        ReedSolomon rs = new ReedSolomon(200, 56);
        byte[][] data = randomData(new Random(3), 200, 4);
        byte[][] full = all(data, rs.encode(data));
        boolean[] present = new boolean[256];
        Arrays.fill(present, true);
        byte[][] work = new byte[256][];
        for (int i = 0; i < 256; i++) {
            work[i] = full[i].clone();
        }
        for (int i = 0; i < 56; i++) { // lose the first 56 data shards
            present[i] = false;
            work[i] = null;
        }
        rs.reconstruct(work, present);
        assertArrayEquals(full, work);
        assertThrows(IllegalArgumentException.class, () -> new ReedSolomon(200, 57));
    }

    @Test
    void tooManyErasuresRejectedAndShardsUntouched() {
        ReedSolomon rs = new ReedSolomon(3, 2);
        byte[][] data = randomData(new Random(1), 3, 4);
        byte[][] full = all(data, rs.encode(data));
        boolean[] present = {true, false, false, false, true};
        byte[][] work = new byte[5][];
        for (int i = 0; i < 5; i++) {
            work[i] = present[i] ? full[i].clone() : new byte[4];
        }
        assertThrows(IllegalArgumentException.class, () -> rs.reconstruct(work, present));
        assertArrayEquals(new byte[4], work[1]);
    }

    @Test
    void verifyDetectsAnySingleByteFlip() {
        ReedSolomon rs = new ReedSolomon(4, 2);
        byte[][] data = randomData(new Random(5), 4, 6);
        byte[][] full = all(data, rs.encode(data));
        assertTrue(rs.verify(full));
        for (int s = 0; s < 6; s++) {
            for (int b = 0; b < 6; b++) {
                byte[][] bad = new byte[6][];
                for (int i = 0; i < 6; i++) {
                    bad[i] = full[i].clone();
                }
                bad[s][b] ^= 0x01;
                assertFalse(rs.verify(bad), "shard " + s + " byte " + b);
            }
        }
    }

    @Test
    void emptyShardsSupported() {
        ReedSolomon rs = new ReedSolomon(2, 2);
        byte[][] parity = rs.encode(new byte[][] {new byte[0], new byte[0]});
        assertEquals(2, parity.length);
        assertEquals(0, parity[0].length);
    }

    @Test
    void argumentValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ReedSolomon(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReedSolomon(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReedSolomon(129, 128));
        ReedSolomon rs = new ReedSolomon(2, 1);
        assertThrows(IllegalArgumentException.class, () -> rs.encode(new byte[][] {{1}}));
        assertThrows(IllegalArgumentException.class, () -> rs.encode(new byte[][] {{1}, {1, 2}}));
        assertThrows(IllegalArgumentException.class,
                () -> rs.reconstruct(new byte[][] {{1}, {1, 2}, {3}}, new boolean[] {true, true, true}));
        assertThrows(IllegalArgumentException.class,
                () -> rs.reconstruct(new byte[][] {{1}, {1}, {3}}, new boolean[] {true, true}));
    }
}
