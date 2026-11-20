package ae.roaring;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class RoaringBitmapTest {

    static long u(int x) { return x & 0xFFFFFFFFL; }

    static RoaringBitmap of(int... xs) {
        RoaringBitmap b = new RoaringBitmap();
        for (int x : xs) b.add(x);
        return b;
    }

    static void assertSame(TreeSet<Long> model, RoaringBitmap b, String ctx) {
        b.checkInvariants();
        assertEquals(model.size(), b.cardinality(), ctx + " cardinality");
        int[] got = b.toArray();
        int i = 0;
        for (long v : model) {
            assertTrue(i < got.length, ctx + " too few elements");
            assertEquals(v, u(got[i]), ctx + " element " + i);
            i++;
        }
        assertEquals(model.size(), got.length, ctx + " length");
    }

    static TreeSet<Long> modelOf(RoaringBitmap b) {
        TreeSet<Long> s = new TreeSet<>();
        b.forEach(x -> s.add(u(x)));
        return s;
    }

    @Test
    void basicAddContainsRemove() {
        RoaringBitmap b = new RoaringBitmap();
        assertTrue(b.isEmpty());
        assertTrue(b.add(5));
        assertFalse(b.add(5));
        assertTrue(b.contains(5));
        assertFalse(b.contains(6));
        assertEquals(1, b.cardinality());
        assertTrue(b.remove(5));
        assertFalse(b.remove(5));
        assertTrue(b.isEmpty());
        assertEquals(0, b.containerCount());
    }

    @Test
    void unsignedOrderAndExtremes() {
        RoaringBitmap b = of(-1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 65535, 65536);
        assertArrayEquals(new int[] {0, 65535, 65536, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}, b.toArray());
        assertEquals(0, b.first());
        assertEquals(-1, b.last());
        assertEquals(4, b.rank(Integer.MAX_VALUE));
        assertEquals(5, b.rank(Integer.MIN_VALUE));
        assertEquals(6, b.rank(-1));
        assertEquals(Integer.MIN_VALUE, b.select(4));
        assertThrows(IndexOutOfBoundsException.class, () -> b.select(6));
        assertThrows(IndexOutOfBoundsException.class, () -> b.select(-1));
        assertThrows(java.util.NoSuchElementException.class, () -> new RoaringBitmap().first());
        assertThrows(java.util.NoSuchElementException.class, () -> new RoaringBitmap().last());
    }

    @Test
    void arrayPromotesToBitmapAt4097AndDemotesBack() {
        RoaringBitmap b = new RoaringBitmap();
        for (int i = 0; i < 4096; i++) b.add(i * 2);
        assertEquals(List.of("array"), b.containerKinds());
        b.add(1);
        assertEquals(List.of("bitmap"), b.containerKinds());
        b.remove(1);
        assertEquals(List.of("array"), b.containerKinds(), "4096 elements must be an array again");
        b.checkInvariants();
        assertEquals(4096, b.cardinality());
    }

    @Test
    void runOptimizeChoosesRunOnlyWhenStrictlySmaller() {
        RoaringBitmap dense = new RoaringBitmap();
        for (int i = 0; i < 5000; i++) dense.add(i);          // bitmap 8192 bytes vs run 6 bytes
        RoaringBitmap sparse = new RoaringBitmap();
        for (int i = 0; i < 100; i++) sparse.add(i * 3);       // array 200 bytes vs 100 runs = 402 bytes
        RoaringBitmap tiny = of(7);                             // array 2 bytes vs run 6 bytes
        RoaringBitmap pair = of(10, 11);                        // array 4 bytes vs run 6 bytes
        RoaringBitmap six = of(1, 2, 3, 4, 5, 6);               // array 12 bytes vs run 6 bytes
        RoaringBitmap three = of(1, 2, 3);                      // array 6 bytes vs run 6 bytes: NOT strictly smaller
        for (RoaringBitmap b : List.of(dense, sparse, tiny, pair, six, three)) b.runOptimize();
        assertEquals(List.of("run"), dense.containerKinds());
        assertEquals(List.of("array"), sparse.containerKinds());
        assertEquals(List.of("array"), tiny.containerKinds());
        assertEquals(List.of("array"), pair.containerKinds());
        assertEquals(List.of("run"), six.containerKinds());
        assertEquals(List.of("array"), three.containerKinds());
        for (RoaringBitmap b : List.of(dense, sparse, tiny, pair, six, three)) b.checkInvariants();
        assertEquals(5000, dense.cardinality());
        assertTrue(dense.contains(4999) && !dense.contains(5000));
    }

    @Test
    void runOptimizeConvertsATiedRunBackToArray() {
        RoaringBitmap b = new RoaringBitmap();
        b.addRange(1, 4);                       // addRange makes a run: 3 values, run 6 bytes == array 6 bytes
        assertEquals(List.of("run"), b.containerKinds());
        b.runOptimize();
        assertEquals(List.of("array"), b.containerKinds(), "a tie must resolve to array");
        assertArrayEquals(new int[] {1, 2, 3}, b.toArray());
    }

    @Test
    void runContainerMutatesAndRoundTripsBackToCanonical() {
        RoaringBitmap b = new RoaringBitmap();
        b.addRange(100, 9000);
        b.runOptimize();
        assertEquals(List.of("run"), b.containerKinds());
        assertTrue(b.remove(500));
        assertFalse(b.contains(500));
        assertTrue(b.add(20000));
        assertEquals(8900 - 1 + 1, b.cardinality());   // 100..8999 is 8900 values, minus 500, plus 20000
        b.checkInvariants();
        assertTrue(b.contains(8999) && b.contains(100) && !b.contains(99) && !b.contains(9000));
    }

    @Test
    void addRangeAcrossKeysAndHugeRangeStaysSmall() {
        RoaringBitmap b = new RoaringBitmap();
        b.addRange(65530, 65540);
        assertArrayEquals(new int[] {65530, 65531, 65532, 65533, 65534, 65535, 65536, 65537, 65538, 65539}, b.toArray());
        b.addRange(5, 5);
        b.addRange(9, 3);
        assertEquals(10, b.cardinality());
        RoaringBitmap all = new RoaringBitmap();
        all.addRange(0, 1L << 32);
        assertEquals(1L << 32, all.cardinality());
        assertEquals(65536, all.containerCount());
        assertTrue(all.containerKinds().stream().allMatch("run"::equals));
        assertTrue(all.contains(-1) && all.contains(0) && all.contains(Integer.MIN_VALUE));
        assertEquals(-1, all.last());
        assertEquals(Integer.MIN_VALUE, all.select(1L << 31));
        assertEquals(1L << 32, all.rank(-1));
        all.removeRange(1, 1L << 32);
        assertArrayEquals(new int[] {0}, all.toArray());
        assertEquals(1, all.containerCount());
        assertThrows(IllegalArgumentException.class, () -> new RoaringBitmap().addRange(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new RoaringBitmap().addRange(0, (1L << 32) + 1));
        assertThrows(IllegalArgumentException.class, () -> new RoaringBitmap().flip(0, (1L << 32) + 1));
    }

    @Test
    void flipTogglesRangeAndTwiceRestores() {
        RoaringBitmap b = of(1, 3, 70000, 70001);
        RoaringBitmap orig = b.copy();
        b.flip(0, 6);
        assertArrayEquals(new int[] {0, 2, 4, 5, 70000, 70001}, b.toArray());
        b.flip(0, 6);
        assertEquals(orig, b);
        b.flip(65536, 140000);            // spans key 1 (holds 70000, 70001) and key 2 (empty)
        assertEquals(1 + 1 + (140000 - 65536 - 2), b.cardinality());
        assertFalse(b.contains(70000));
        assertTrue(b.contains(65536) && b.contains(139999) && !b.contains(140000));
        b.checkInvariants();
    }

    @Test
    void flipOfFullKeyLeavesNoEmptyContainer() {
        RoaringBitmap b = new RoaringBitmap();
        b.addRange(0, 65536);
        b.flip(0, 65536);
        assertTrue(b.isEmpty());
        assertEquals(0, b.containerCount());
        b.checkInvariants();
    }

    @Test
    void randomOperationsMatchTreeSetModel() {
        for (int seed = 0; seed < 12; seed++) {
            Random r = new Random(seed);
            RoaringBitmap b = new RoaringBitmap();
            TreeSet<Long> m = new TreeSet<>();
            // Values clustered in 3 keys (incl. the top key) so containers cross array/bitmap/run boundaries.
            int[] bases = {0, 3 << 16, 0xFFFF0000};
            for (int step = 0; step < 400; step++) {
                int base = bases[r.nextInt(bases.length)];
                int op = r.nextInt(10);
                if (op < 4) {
                    int n = 1 + r.nextInt(600);
                    for (int i = 0; i < n; i++) {
                        int v = base + r.nextInt(r.nextBoolean() ? 9000 : 65536);
                        assertEquals(m.add(u(v)), b.add(v));
                    }
                } else if (op < 6) {
                    int n = 1 + r.nextInt(600);
                    for (int i = 0; i < n; i++) {
                        int v = base + r.nextInt(9000);
                        assertEquals(m.remove(u(v)), b.remove(v));
                    }
                } else if (op == 6) {
                    long s = u(base) + r.nextInt(20000), e = s + r.nextInt(30000);
                    if (e > (1L << 32)) e = 1L << 32;
                    b.addRange(s, e);
                    for (long v = s; v < e; v++) m.add(v);
                } else if (op == 7) {
                    long s = u(base) + r.nextInt(20000), e = s + r.nextInt(30000);
                    if (e > (1L << 32)) e = 1L << 32;
                    b.removeRange(s, e);
                    for (long v = s; v < e; v++) m.remove(v);
                } else if (op == 8) {
                    long s = u(base) + r.nextInt(20000), e = s + r.nextInt(30000);
                    if (e > (1L << 32)) e = 1L << 32;
                    b.flip(s, e);
                    for (long v = s; v < e; v++) { if (!m.remove(v)) m.add(v); }
                } else {
                    b.runOptimize();
                }
                assertSame(m, b, "seed " + seed + " step " + step);
            }
            for (int i = 0; i < 200; i++) {
                int v = bases[r.nextInt(3)] + r.nextInt(70000);
                assertEquals(m.contains(u(v)), b.contains(v));
            }
        }
    }

    static RoaringBitmap randomBitmap(Random r, TreeSet<Long> model) {
        RoaringBitmap b = new RoaringBitmap();
        int shape = r.nextInt(4);
        int[] bases = {0, 1 << 16, 5 << 16, 0x80000000};
        for (int base : bases) {
            if (r.nextInt(4) == 0) continue;
            switch (shape) {
                case 0 -> { for (int i = 0, n = r.nextInt(200); i < n; i++) { int v = base + r.nextInt(65536); b.add(v); model.add(u(v)); } }
                case 1 -> { for (int i = 0, n = 4000 + r.nextInt(3000); i < n; i++) { int v = base + r.nextInt(65536); b.add(v); model.add(u(v)); } }
                case 2 -> { long s = u(base) + r.nextInt(30000); long e = s + r.nextInt(30000); b.addRange(s, e); for (long v = s; v < e; v++) model.add(v); }
                default -> { for (int i = 0, n = r.nextInt(5000); i < n; i++) { int v = base + r.nextInt(8000); b.add(v); model.add(u(v)); } }
            }
        }
        if (r.nextBoolean()) b.runOptimize();
        return b;
    }

    @Test
    void setOperationsMatchModelAndNeverMutateOperands() {
        for (int seed = 0; seed < 60; seed++) {
            Random r = new Random(1000 + seed);
            TreeSet<Long> ma = new TreeSet<>(), mb = new TreeSet<>();
            RoaringBitmap a = randomBitmap(r, ma), b = randomBitmap(r, mb);
            RoaringBitmap aCopy = a.copy(), bCopy = b.copy();

            TreeSet<Long> and = new TreeSet<>(ma); and.retainAll(mb);
            TreeSet<Long> or = new TreeSet<>(ma); or.addAll(mb);
            TreeSet<Long> xor = new TreeSet<>(or); xor.removeAll(and);
            TreeSet<Long> diff = new TreeSet<>(ma); diff.removeAll(mb);
            String ctx = "seed " + seed;
            RoaringBitmap rAnd = RoaringBitmap.and(a, b), rOr = RoaringBitmap.or(a, b);
            RoaringBitmap rXor = RoaringBitmap.xor(a, b), rDiff = RoaringBitmap.andNot(a, b);
            assertSame(and, rAnd, ctx + " and");
            assertSame(or, rOr, ctx + " or");
            assertSame(xor, rXor, ctx + " xor");
            assertSame(diff, rDiff, ctx + " andNot");
            for (RoaringBitmap res : List.of(rAnd, rOr, rXor, rDiff)) {
                assertFalse(res.containerKinds().contains("run"), ctx + " set-op results hold array/bitmap only");
            }
            assertSame(ma, a, ctx + " a unchanged");
            assertSame(mb, b, ctx + " b unchanged");
            assertEquals(aCopy, a);
            assertEquals(bCopy, b);
            // Mutating a result must not leak into the operands (no shared containers).
            RoaringBitmap alias = RoaringBitmap.or(a, new RoaringBitmap());
            alias.add(12345678);
            alias.remove(a.isEmpty() ? 0 : a.first());
            assertSame(ma, a, ctx + " no aliasing");
        }
    }

    @Test
    void rankAndSelectMatchSortedArray() {
        Random r = new Random(77);
        TreeSet<Long> m = new TreeSet<>();
        RoaringBitmap b = randomBitmap(r, m);
        b.add(-5); m.add(u(-5));
        b.runOptimize();
        Long[] sorted = m.toArray(new Long[0]);
        for (int i = 0; i < sorted.length; i += 37) {
            assertEquals(sorted[i], u(b.select(i)));
            assertEquals(i + 1, b.rank((int) (long) sorted[i]));
            if (sorted[i] > 0) assertEquals(i, b.rank((int) (sorted[i] - 1)), "elements strictly below #" + i);
        }
        assertEquals(0, new RoaringBitmap().rank(0));
        assertEquals(sorted.length, b.rank(-1));
    }

    @Test
    void equalsAndHashCodeIgnoreContainerKind() {
        RoaringBitmap a = new RoaringBitmap();
        for (int i = 0; i < 20000; i++) a.add(i);   // plain adds: addRange would create a run container
        RoaringBitmap b = a.copy();
        b.runOptimize();
        assertEquals(List.of("bitmap"), a.containerKinds());
        assertEquals(List.of("run"), b.containerKinds());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.remove(19999);
        assertNotEquals(a, b);
        assertNotEquals(a, null);
        assertNotEquals(a, "x");
        assertEquals(new RoaringBitmap(), new RoaringBitmap());
    }

    // ---------------- serialisation ----------------

    static RoaringBitmap allKinds() {
        RoaringBitmap b = new RoaringBitmap();
        for (int i = 0; i < 50; i++) b.add(i * 7);                           // key 0: array
        for (int i = 0; i < 6000; i++) b.add((1 << 16) + i * 5 % 65536);     // key 1: bitmap
        b.addRange(2 << 16, (2 << 16) + 30000);                              // key 2: run after optimise
        b.add(-1);                                                           // key 65535: array
        b.runOptimize();
        // runOptimize turns key 1 into a run only if smaller; check kinds explicitly in the test below
        return b;
    }

    @Test
    void serializationRoundTripCoversAllKinds() {
        RoaringBitmap b = allKinds();
        assertTrue(b.containerKinds().containsAll(List.of("array", "bitmap", "run")), "kinds: " + b.containerKinds());
        RoaringBitmap back = RoaringBitmap.deserialize(b.serialize());
        assertEquals(b, back);
        assertEquals(b.containerKinds(), back.containerKinds(), "kinds are preserved by the format");
        back.checkInvariants();
        assertEquals(new RoaringBitmap(), RoaringBitmap.deserialize(new RoaringBitmap().serialize()));
    }

    @Test
    void everyTruncationAndEveryByteFlipIsRejected() {
        RoaringBitmap b = new RoaringBitmap();
        b.add(1); b.add(900); b.addRange(70000, 70100); b.add(-2);
        b.runOptimize();
        byte[] img = b.serialize();
        for (int len = 0; len < img.length; len++) {
            byte[] cut = java.util.Arrays.copyOf(img, len);
            assertThrows(CorruptBitmapException.class, () -> RoaringBitmap.deserialize(cut), "truncated to " + len);
        }
        for (int i = 0; i < img.length; i++) {
            byte[] bad = img.clone();
            bad[i] ^= 0x40;
            assertThrows(CorruptBitmapException.class, () -> RoaringBitmap.deserialize(bad), "flipped byte " + i);
        }
        byte[] extra = java.util.Arrays.copyOf(img, img.length + 1);
        assertThrows(CorruptBitmapException.class, () -> RoaringBitmap.deserialize(extra));
        assertEquals(b, RoaringBitmap.deserialize(img));
    }

    /** Builds an image with a valid CRC around the given body (after the 5-byte header and N). */
    static byte[] sealed(int version, int n, byte[] containers) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.writeInt(RoaringBitmap.MAGIC);
        o.writeByte(version);
        o.writeInt(n);
        o.write(containers);
        o.flush();
        CRC32 crc = new CRC32();
        crc.update(bo.toByteArray());
        o.writeInt((int) crc.getValue());
        o.flush();
        return bo.toByteArray();
    }

    static byte[] arrayContainer(int key, int... vals) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.writeShort(key);
        o.writeByte(0);
        o.writeInt(vals.length);
        for (int v : vals) o.writeShort(v);
        return bo.toByteArray();
    }

    static byte[] runContainer(int key, int... startLenMinus1) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.writeShort(key);
        o.writeByte(2);
        o.writeInt(startLenMinus1.length / 2);
        for (int v : startLenMinus1) o.writeShort(v);
        return bo.toByteArray();
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (byte[] p : parts) bo.writeBytes(p);
        return bo.toByteArray();
    }

    static void rejects(byte[] img, String messagePart) {
        CorruptBitmapException e = assertThrows(CorruptBitmapException.class, () -> RoaringBitmap.deserialize(img));
        assertTrue(e.getMessage().contains(messagePart), "message was: " + e.getMessage());
    }

    @Test
    void craftedImagesWithValidCrcAreRejectedForStructure() throws IOException {
        // Control: well-formed hand-built images load.
        RoaringBitmap ok = RoaringBitmap.deserialize(sealed(1, 2, cat(arrayContainer(0, 1, 5, 9), arrayContainer(3, 7))));
        assertArrayEquals(new int[] {1, 5, 9, (3 << 16) | 7}, ok.toArray());
        RoaringBitmap okRun = RoaringBitmap.deserialize(sealed(1, 1, runContainer(0, 10, 4, 20, 0)));
        assertArrayEquals(new int[] {10, 11, 12, 13, 14, 20}, okRun.toArray());

        rejects(sealed(2, 0, new byte[0]), "version");
        rejects(sealed(1, 2, cat(arrayContainer(3, 1), arrayContainer(3, 2))), "keys");
        rejects(sealed(1, 2, cat(arrayContainer(4, 1), arrayContainer(3, 2))), "keys");
        rejects(sealed(1, 1, arrayContainer(0, 5, 5)), "array");
        rejects(sealed(1, 1, arrayContainer(0, 9, 5)), "array");
        rejects(sealed(1, 1, arrayContainer(0)), "empty");
        rejects(sealed(1, 1, runContainer(0, 10, 4, 14, 0)), "run");          // adjacent: 10..14 then 14 overlaps
        rejects(sealed(1, 1, runContainer(0, 10, 4, 15, 0)), "run");          // adjacent (14 then 15) must be merged
        rejects(sealed(1, 1, runContainer(0, 20, 0, 10, 0)), "run");          // out of order
        rejects(sealed(1, 1, runContainer(0, 65530, 6)), "run");              // 65530+6 = 65536 > 65535
        rejects(sealed(1, 1, runContainer(0)), "empty");
        rejects(sealed(1, 1, cat(new byte[] {0, 0, 9, 0, 0, 0, 1, 0, 1})), "kind");
        rejects(sealed(1, 3, arrayContainer(0, 1)), "truncated");
    }
}
