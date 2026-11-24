package ae.rope;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RopeBalanceTest {

    /** AVL bound: height <= 1.4405 * log2(leaves + 2), plus slack of 2 for the leaf level. */
    private static void assertLogDepth(Rope r) {
        double bound = 1.45 * Math.log(r.leafCount() + 2) / Math.log(2) + 2;
        assertTrue(r.depth() <= bound, "depth " + r.depth() + " > " + bound + " for " + r.leafCount() + " leaves");
    }

    @Test
    void appendingSingleCharsStaysBalancedAndDense() {
        int n = 100_000;
        Rope r = Rope.empty();
        for (int i = 0; i < n; i++) {
            r = r.concat(Rope.of(String.valueOf((char) ('a' + i % 26))));
        }
        assertEquals(n, r.length());
        r.check();
        assertLogDepth(r);
        assertTrue(r.leafCount() <= n / Rope.LEAF_MAX + 2, "leaves " + r.leafCount());
        for (int i = 0; i < n; i += 997) {
            assertEquals((char) ('a' + i % 26), r.charAt(i));
        }
    }

    @Test
    void prependingSingleCharsStaysBalancedAndDense() {
        int n = 100_000;
        Rope r = Rope.empty();
        for (int i = 0; i < n; i++) {
            r = Rope.of(String.valueOf((char) ('a' + i % 26))).concat(r);
        }
        assertEquals(n, r.length());
        r.check();
        assertLogDepth(r);
        assertTrue(r.leafCount() <= n / Rope.LEAF_MAX + 2, "leaves " + r.leafCount());
        assertEquals((char) ('a' + (n - 1) % 26), r.charAt(0));
    }

    @Test
    void ofBuildsABalancedTreeOfFullLeaves() {
        for (int n : new int[] {0, 1, 32, 33, 64, 65, 1000, 4096, 4097}) {
            Rope r = Rope.of("z".repeat(n));
            r.check();
            int expectedLeaves = n == 0 ? 1 : (n + Rope.LEAF_MAX - 1) / Rope.LEAF_MAX; // empty rope = one empty leaf
            assertEquals(expectedLeaves, r.leafCount(), "leaf count for n=" + n);
            assertLogDepth(r);
        }
    }

    @Test
    void joiningVeryUnequalRopesStaysBalanced() {
        Rope big = Rope.of("q".repeat(200_000));
        Rope small = Rope.of("tiny");
        Rope a = big.concat(small);
        Rope b = small.concat(big);
        Rope c = small.concat(big).concat(small);
        for (Rope r : new Rope[] {a, b, c}) {
            r.check();
            assertLogDepth(r);
        }
        assertEquals(200_004, a.length());
        assertEquals('t', b.charAt(0));
        assertEquals('y', c.charAt(c.length() - 1));
    }

    @Test
    void randomEditsOnALargeRopeStayBalancedAndFast() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            Random rnd = new Random(2024);
            Rope r = Rope.of("abcdefghij".repeat(100_000)); // 1,000,000 chars
            long expectedLen = r.length();
            for (int i = 0; i < 20_000; i++) {
                int len = r.length();
                if (rnd.nextBoolean()) {
                    int at = rnd.nextInt(len + 1);
                    r = r.insert(at, "XYZ");
                    expectedLen += 3;
                } else {
                    int a = rnd.nextInt(len - 5);
                    r = r.delete(a, a + 5);
                    expectedLen -= 5;
                }
            }
            assertEquals(expectedLen, r.length());
            r.check();
            assertLogDepth(r);
        });
    }
}
