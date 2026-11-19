package ae.rsync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DeltaEncoderTest {
    private static Delta delta(byte[] basis, byte[] target, int block) {
        return DeltaEncoder.compute(Signature.of(basis, block), target);
    }

    private static Delta.Copy copy(long off, int len) {
        return new Delta.Copy(off, len);
    }

    private static Delta.Literal lit(byte[] d) {
        return new Delta.Literal(d);
    }

    /** Ops must be canonical: no empty op, no two adjacent literals, no two copies contiguous in the basis. */
    private static void assertCanonical(Delta d) {
        Delta.Op prev = null;
        for (Delta.Op op : d.ops()) {
            if (op instanceof Delta.Copy c) {
                assertTrue(c.length() > 0, "empty copy");
                if (prev instanceof Delta.Copy p) {
                    assertNotEquals(p.offset() + p.length(), c.offset(), "unmerged contiguous copies");
                }
            } else if (op instanceof Delta.Literal l) {
                assertTrue(l.data().length > 0, "empty literal");
                assertFalse(prev instanceof Delta.Literal, "adjacent literals");
            }
            prev = op;
        }
    }

    @Test
    void identicalFileIsOneMergedCopyIncludingShortTail() {
        byte[] basis = TestData.random(10, 45);
        Delta d = delta(basis, basis, 10);
        assertEquals(List.of(copy(0, 45)), d.ops());
        assertEquals(45, d.targetLength());
    }

    @Test
    void appendedDataIsCopyThenLiteral() {
        byte[] basis = TestData.random(11, 40);
        byte[] extra = TestData.random(12, 7);
        Delta d = delta(basis, TestData.concat(basis, extra), 10);
        assertEquals(List.of(copy(0, 40), lit(extra)), d.ops());
    }

    @Test
    void prefixInsertionIsLiteralThenCopy() {
        byte[] basis = TestData.random(13, 100);
        byte[] prefix = TestData.random(14, 13);
        Delta d = delta(basis, TestData.concat(prefix, basis), 10);
        assertEquals(List.of(lit(prefix), copy(0, 100)), d.ops());
    }

    @Test
    void truncationKeepsWholeBlocksAndSendsPartialTailAsLiteral() {
        byte[] basis = TestData.random(15, 45);
        byte[] target = TestData.slice(basis, 0, 35);
        Delta d = delta(basis, target, 10);
        // blocks 0..2 match; bytes 30..35 are a 5-byte stub that equals no block (the short block is basis[40..45])
        assertEquals(List.of(copy(0, 30), lit(TestData.slice(basis, 30, 35))), d.ops());
    }

    @Test
    void emptyTargetAndEmptyBasis() {
        byte[] basis = TestData.random(16, 30);
        assertEquals(List.of(), delta(basis, new byte[0], 10).ops());
        byte[] target = TestData.random(17, 12);
        assertEquals(List.of(lit(target)), delta(new byte[0], target, 10).ops());
        assertEquals(List.of(), delta(new byte[0], new byte[0], 10).ops());
    }

    @Test
    void movedBlocksBecomeSeparateCopies() {
        byte[] a = TestData.random(18, 10);
        byte[] b = TestData.random(19, 10);
        byte[] c = TestData.random(20, 10);
        Delta d = delta(TestData.concat(a, b, c), TestData.concat(c, a, b), 10);
        // c is at basis 20; a,b are contiguous at 0 and merge into one copy
        assertEquals(List.of(copy(20, 10), copy(0, 20)), d.ops());
    }

    @Test
    void shortBasisTailMatchesOnlyWhenExactlyThatManyBytesRemain() {
        byte[] body = TestData.random(21, 100);
        byte[] tail = TestData.random(22, 5);
        byte[] basis = TestData.concat(body, tail); // blocks of 10: ten full + a 5-byte tail
        byte[] x = TestData.random(23, 13);

        Delta atEnd = delta(basis, TestData.concat(x, tail), 10);
        assertEquals(List.of(lit(x), copy(100, 5)), atEnd.ops());

        // The same 5 bytes at the START of a longer target must not be taken from the short block.
        byte[] y = TestData.random(24, 20);
        byte[] target = TestData.concat(tail, y);
        Delta atStart = delta(basis, target, 10);
        assertEquals(List.of(lit(target)), atStart.ops());
    }

    @Test
    void weakCollisionIsNeverCopied() throws Exception {
        // Second difference (+1, -2, +1) leaves both a and b unchanged: 5,5,5 and 6,3,6 collide.
        byte[] p = {5, 5, 5};
        byte[] q = {6, 3, 6};
        assertEquals(Rolling.weak(p, 0, 3), Rolling.weak(q, 0, 3));
        assertFalse(java.util.Arrays.equals(p, q));
        Delta d = delta(p, q, 3);
        assertEquals(List.of(lit(q)), d.ops());
        assertArrayEquals(q, Patcher.apply(p, d));
        // control: the genuine block is still found
        assertEquals(List.of(copy(0, 3)), delta(p, p, 3).ops());
    }

    @Test
    void blockSizeOneWorksOnShuffledBytes() throws Exception {
        byte[] basis = TestData.random(25, 60);
        byte[] target = basis.clone();
        Random r = new Random(26);
        for (int i = target.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            byte t = target[i];
            target[i] = target[j];
            target[j] = t;
        }
        Delta d = delta(basis, target, 1);
        assertArrayEquals(target, Patcher.apply(basis, d));
        assertCanonical(d);
    }

    @Test
    void singleInsertionCostsAtMostInsertedBytesPlusOneBlock() throws Exception {
        int block = 64;
        byte[] basis = TestData.random(27, 10_000);
        for (int k : new int[] {1, 5, 63, 64, 200}) {
            byte[] ins = TestData.random(100 + k, k);
            int at = 3333;
            byte[] target = TestData.concat(TestData.slice(basis, 0, at), ins, TestData.slice(basis, at, basis.length));
            Delta d = delta(basis, target, block);
            long lits = TestData.literalBytes(d);
            assertTrue(lits >= k, "literals " + lits + " must cover the " + k + " inserted bytes");
            assertTrue(lits <= k + block, "k=" + k + " literals " + lits);
            assertArrayEquals(target, Patcher.apply(basis, d));
        }
    }

    @Test
    void singleDeletionCostsAtMostOneBlockOfLiterals() throws Exception {
        int block = 64;
        byte[] basis = TestData.random(28, 10_000);
        byte[] target = TestData.concat(TestData.slice(basis, 0, 4000), TestData.slice(basis, 4010, basis.length));
        Delta d = delta(basis, target, block);
        assertTrue(TestData.literalBytes(d) <= block, "literals " + TestData.literalBytes(d));
        assertArrayEquals(target, Patcher.apply(basis, d));
    }

    @Test
    void deltaRecordsTargetDigest() {
        byte[] target = TestData.random(29, 77);
        Delta d = delta(TestData.random(30, 50), target, 8);
        assertArrayEquals(Delta.sha256(target), d.targetSha256());
        assertEquals(77, d.targetLength());
    }

    @Test
    void randomEditsRoundTripAcrossBlockSizes() throws Exception {
        Random r = new Random(31);
        int[] blocks = {1, 2, 3, 5, 16, 64, 700};
        for (int iter = 0; iter < 300; iter++) {
            int n = r.nextInt(2500);
            byte[] basis = r.nextInt(3) == 0 ? lowEntropy(r, n) : TestData.random(r.nextLong(), n);
            byte[] target = mutate(r, basis);
            int block = blocks[r.nextInt(blocks.length)];
            Delta d = delta(basis, target, block);
            assertCanonical(d);
            assertArrayEquals(target, Patcher.apply(basis, d), "iter " + iter + " block " + block);
            Delta back = Delta.parse(d.toBytes());
            assertEquals(d, back);
            long copied = d.targetLength() - TestData.literalBytes(d);
            assertTrue(copied >= 0);
        }
    }

    private static byte[] lowEntropy(Random r, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ('a' + r.nextInt(2));
        }
        return b;
    }

    private static byte[] mutate(Random r, byte[] in) {
        byte[] cur = in;
        int edits = r.nextInt(5);
        for (int e = 0; e < edits; e++) {
            int at = cur.length == 0 ? 0 : r.nextInt(cur.length + 1);
            switch (r.nextInt(4)) {
                case 0 -> cur = TestData.concat(TestData.slice(cur, 0, at), TestData.random(r.nextLong(), r.nextInt(50)),
                        TestData.slice(cur, at, cur.length));
                case 1 -> {
                    int to = Math.min(cur.length, at + r.nextInt(80));
                    cur = TestData.concat(TestData.slice(cur, 0, at), TestData.slice(cur, to, cur.length));
                }
                case 2 -> {
                    if (cur.length > 0) {
                        byte[] c = cur.clone();
                        c[Math.min(at, c.length - 1)] ^= (byte) (1 + r.nextInt(255));
                        cur = c;
                    }
                }
                default -> {
                    // move a slice elsewhere
                    int to = Math.min(cur.length, at + r.nextInt(100));
                    byte[] moved = TestData.slice(cur, at, to);
                    byte[] rest = TestData.concat(TestData.slice(cur, 0, at), TestData.slice(cur, to, cur.length));
                    int dest = rest.length == 0 ? 0 : r.nextInt(rest.length + 1);
                    cur = TestData.concat(TestData.slice(rest, 0, dest), moved, TestData.slice(rest, dest, rest.length));
                }
            }
        }
        return cur;
    }

    @Test
    void manyDuplicateBlocksStayCorrectAndLiteralFree() throws Exception {
        byte[] block = TestData.random(32, 16);
        byte[] basis = new byte[16 * 20];
        for (int i = 0; i < 20; i++) {
            System.arraycopy(block, 0, basis, i * 16, 16);
        }
        byte[] target = TestData.concat(block, block, block);
        Delta d = delta(basis, target, 16);
        assertEquals(0, TestData.literalBytes(d));
        assertArrayEquals(target, Patcher.apply(basis, d));
        List<Delta.Op> ops = new ArrayList<>(d.ops());
        assertFalse(ops.isEmpty());
    }
}
