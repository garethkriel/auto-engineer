package ae.columnar;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class EncodedColumnTest {

    static final long MIN = Long.MIN_VALUE;
    static final long MAX = Long.MAX_VALUE;

    /** Datasets that every encoding must handle. */
    static long[][] datasets() {
        Random rnd = new Random(11);
        long[] randomWide = new long[300];
        long[] narrow = new long[300];
        long[] lowCard = new long[300];
        for (int i = 0; i < 300; i++) {
            randomWide[i] = rnd.nextLong();
            narrow[i] = 1_000_000_000L + rnd.nextInt(50);
            lowCard[i] = new long[] {-7_000_000_000_000L, 0, 5_000_000_000_000L}[rnd.nextInt(3)];
        }
        long[] runs = new long[200];
        for (int i = 0; i < 200; i++) {
            runs[i] = i / 40; // five runs of 40
        }
        return new long[][] {
            {42},
            {5, 5, 5, 5},
            {MIN, MAX, 0, -1, 1, MIN, MAX},
            {MAX, MIN},                    // delta wraps around
            {MIN, MIN, MIN},
            {-3, -2, -1, 0, 1, 2, 3},
            {10, 9, 8, 7, 100, 1, 1, 2},
            randomWide, narrow, lowCard, runs,
        };
    }

    static long[] naiveScan(long[] v, Op op, long probe) {
        return java.util.stream.IntStream.range(0, v.length).filter(i -> op.test(v[i], probe)).mapToLong(i -> i).toArray();
    }

    static long[] bits(BitSet s) {
        return s.stream().mapToLong(i -> i).toArray();
    }

    @Test
    void everyEncodingRoundTripsEveryDataset() {
        for (long[] data : datasets()) {
            for (Encoding e : Encoding.values()) {
                EncodedColumn c = EncodedColumn.encode(data, e);
                String msg = e + " n=" + data.length;
                assertEquals(e, c.encoding(), msg);
                assertEquals(data.length, c.size(), msg);
                assertArrayEquals(data, c.decode(), msg);
                for (int i = 0; i < data.length; i++) {
                    assertEquals(data[i], c.get(i), msg + " i=" + i);
                }
                assertEquals(Arrays.stream(data).min().getAsLong(), c.min(), msg);
                assertEquals(Arrays.stream(data).max().getAsLong(), c.max(), msg);
            }
        }
    }

    @Test
    void getOutOfRangeAndEmptyInputAreRejected() {
        EncodedColumn c = EncodedColumn.encode(new long[] {1, 2, 3}, Encoding.DELTA);
        assertThrows(IndexOutOfBoundsException.class, () -> c.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> c.get(3));
        for (Encoding e : Encoding.values()) {
            assertThrows(IllegalArgumentException.class, () -> EncodedColumn.encode(new long[0], e));
        }
        assertThrows(NullPointerException.class, () -> EncodedColumn.encode(null, Encoding.PLAIN));
    }

    @Test
    void encodeBestPicksTheSmallestShape() {
        long[] constant = new long[1000];
        Arrays.fill(constant, 77);
        // constant column: FOR with width 0 = 5+8+1+4 = 18 bytes beats RLE = 5+4+12+4 = 25
        EncodedColumn c0 = EncodedColumn.encodeBest(constant);
        assertEquals(Encoding.FOR, c0.encoding());
        assertEquals(18, c0.toBytes().length);

        long[] twoRuns = new long[2000];
        Arrays.fill(twoRuns, 1000, 2000, 9);
        assertEquals(Encoding.RLE, EncodedColumn.encodeBest(twoRuns).encoding());

        Random rnd = new Random(3);
        long[] lowCard = new long[2000];
        for (int i = 0; i < lowCard.length; i++) {
            lowCard[i] = new long[] {1_000_000_000_000L, 5_000_000_000_000L, -7_000_000_000_000L}[rnd.nextInt(3)];
        }
        assertEquals(Encoding.DICT, EncodedColumn.encodeBest(lowCard).encoding());

        long[] narrow = new long[2000]; // 1000 distinct values near 1e9: FOR 10 bits beats DICT (8000-byte dictionary)
        for (int i = 0; i < narrow.length; i++) {
            narrow[i] = 1_000_000_000L + rnd.nextInt(1000);
        }
        assertEquals(Encoding.FOR, EncodedColumn.encodeBest(narrow).encoding());

        long[] timestamps = new long[2000]; // ~1000 apart: deltas need ~4 bits, FOR range needs ~21
        long t = 1_700_000_000_000L;
        for (int i = 0; i < timestamps.length; i++) {
            t += 997 + rnd.nextInt(7);
            timestamps[i] = t;
        }
        assertEquals(Encoding.DELTA, EncodedColumn.encodeBest(timestamps).encoding());

        long[] noise = new long[2000];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = rnd.nextLong();
        }
        assertEquals(Encoding.PLAIN, EncodedColumn.encodeBest(noise).encoding());
    }

    @Test
    void encodeBestIsNeverLargerThanAnyOtherEncodingAndBreaksTiesByEnumOrder() {
        for (long[] data : datasets()) {
            EncodedColumn best = EncodedColumn.encodeBest(data);
            int bestLen = best.toBytes().length;
            for (Encoding e : Encoding.values()) {
                int len = EncodedColumn.encode(data, e).toBytes().length;
                assertTrue(bestLen <= len, best.encoding() + " " + bestLen + " vs " + e + " " + len);
                if (len == bestLen) {
                    assertTrue(best.encoding().ordinal() <= e.ordinal(), "tie must go to the earlier enum value");
                }
            }
        }
        // single value, sizes by layout: PLAIN 5+8+4=17, FOR/DELTA 5+8+1+4=18, DICT 5+4+8+1+4=22, RLE 5+4+12+4=25
        EncodedColumn single = EncodedColumn.encodeBest(new long[] {42});
        assertEquals(Encoding.PLAIN, single.encoding());
        assertEquals(17, single.toBytes().length);
        assertEquals(18, EncodedColumn.encode(new long[] {42}, Encoding.FOR).toBytes().length);
        assertEquals(18, EncodedColumn.encode(new long[] {42}, Encoding.DELTA).toBytes().length);
        assertEquals(22, EncodedColumn.encode(new long[] {42}, Encoding.DICT).toBytes().length);
        assertEquals(25, EncodedColumn.encode(new long[] {42}, Encoding.RLE).toBytes().length);
    }

    @Test
    void scanMatchesNaiveLoopForEveryEncodingOpAndProbe() {
        for (long[] data : datasets()) {
            TreeSet<Long> probes = new TreeSet<>(Arrays.asList(MIN, MAX, 0L, -1L, 1L));
            for (int i = 0; i < data.length; i += Math.max(1, data.length / 12)) {
                probes.add(data[i]);
                probes.add(data[i] - 1);
                probes.add(data[i] + 1);
            }
            for (Encoding e : Encoding.values()) {
                EncodedColumn c = EncodedColumn.encode(data, e);
                for (Op op : Op.values()) {
                    for (long p : probes) {
                        assertArrayEquals(naiveScan(data, op, p), bits(c.scan(op, p)),
                            e + " " + op + " " + p + " n=" + data.length);
                    }
                }
            }
        }
    }

    @Test
    void scanResultIsAFreshBitSetEachTime() {
        EncodedColumn c = EncodedColumn.encode(new long[] {1, 2, 3}, Encoding.RLE);
        BitSet a = c.scan(Op.GE, 0); // zone map says "everything"
        a.clear();
        assertEquals(3, c.scan(Op.GE, 0).cardinality());
        BitSet b = c.scan(Op.LT, 0); // zone map says "nothing"
        b.set(0, 10);
        assertEquals(0, c.scan(Op.LT, 0).cardinality());
    }

    @Test
    void opZoneMapVerdicts() {
        // zone [10, 20]
        assertEquals(Op.Verdict.NONE, Op.EQ.prune(10, 20, 9));
        assertEquals(Op.Verdict.NONE, Op.EQ.prune(10, 20, 21));
        assertEquals(Op.Verdict.MAYBE, Op.EQ.prune(10, 20, 10));
        assertEquals(Op.Verdict.ALL, Op.EQ.prune(7, 7, 7));
        assertEquals(Op.Verdict.NONE, Op.NE.prune(7, 7, 7));
        assertEquals(Op.Verdict.ALL, Op.NE.prune(10, 20, 21));
        assertEquals(Op.Verdict.MAYBE, Op.NE.prune(10, 20, 15));
        assertEquals(Op.Verdict.ALL, Op.LT.prune(10, 20, 21));
        assertEquals(Op.Verdict.MAYBE, Op.LT.prune(10, 20, 20));
        assertEquals(Op.Verdict.NONE, Op.LT.prune(10, 20, 10));
        assertEquals(Op.Verdict.ALL, Op.LE.prune(10, 20, 20));
        assertEquals(Op.Verdict.NONE, Op.LE.prune(10, 20, 9));
        assertEquals(Op.Verdict.ALL, Op.GT.prune(10, 20, 9));
        assertEquals(Op.Verdict.NONE, Op.GT.prune(10, 20, 20));
        assertEquals(Op.Verdict.ALL, Op.GE.prune(10, 20, 10));
        assertEquals(Op.Verdict.NONE, Op.GE.prune(10, 20, 21));
        // extremes: no overflow when probing with MIN/MAX
        assertEquals(Op.Verdict.NONE, Op.LT.prune(MIN, MAX, MIN));
        assertEquals(Op.Verdict.NONE, Op.GT.prune(MIN, MAX, MAX));
        assertEquals(Op.Verdict.ALL, Op.GE.prune(MIN, MAX, MIN));
    }

    // ---------------------------------------------------------------- serialisation

    @Test
    void serialisationRoundTrips() {
        for (long[] data : datasets()) {
            for (Encoding e : Encoding.values()) {
                EncodedColumn c = EncodedColumn.encode(data, e);
                EncodedColumn back = EncodedColumn.fromBytes(c.toBytes());
                assertEquals(e, back.encoding());
                assertArrayEquals(data, back.decode());
                assertEquals(c.min(), back.min());
                assertEquals(c.max(), back.max());
                assertArrayEquals(c.toBytes(), back.toBytes());
            }
        }
    }

    @Test
    void everyTruncationIsRejected() {
        for (Encoding e : Encoding.values()) {
            byte[] full = EncodedColumn.encode(datasets()[2], e).toBytes();
            for (int len = 0; len < full.length; len++) {
                final int l = len;
                assertThrows(IllegalArgumentException.class,
                    () -> EncodedColumn.fromBytes(Arrays.copyOf(full, l)), e + " len " + l);
            }
        }
    }

    @Test
    void everyByteFlipIsRejected() {
        for (Encoding e : Encoding.values()) {
            byte[] full = EncodedColumn.encode(new long[] {3, 3, 4, 9, 9, 9, -2}, e).toBytes();
            for (int i = 0; i < full.length; i++) {
                for (int x : new int[] {0x01, 0x80, 0xFF}) {
                    byte[] bad = full.clone();
                    bad[i] ^= (byte) x;
                    assertThrows(IllegalArgumentException.class, () -> EncodedColumn.fromBytes(bad));
                }
            }
        }
    }

    @Test
    void trailingBytesAreRejected() {
        byte[] full = EncodedColumn.encode(new long[] {1, 2}, Encoding.PLAIN).toBytes();
        byte[] longer = Arrays.copyOf(full, full.length + 1);
        assertThrows(IllegalArgumentException.class, () -> EncodedColumn.fromBytes(longer));
    }

    // ---------------------------------------------------------------- crafted images re-sealed with a valid CRC

    private static void rejected(String why, byte[] body) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> EncodedColumn.fromBytes(Img.seal(body)), why);
        assertNotNull(ex.getMessage());
    }

    @Test
    void controlImagesLoad() {
        // each control differs from a rejected image below only by the one defect
        assertArrayEquals(new long[] {7, 7, 8}, EncodedColumn.fromBytes(Img.seal(Img.rle(3, new long[] {7, 8}, new int[] {2, 1}))).decode());
        assertArrayEquals(new long[] {10, 20, 10}, EncodedColumn.fromBytes(Img.seal(Img.dict(3, new long[] {10, 20}, 1, 0b010L))).decode());
        assertArrayEquals(new long[] {5, 6, 7}, EncodedColumn.fromBytes(Img.seal(Img.frameOfReference(3, 5, 2, 0b10_01_00L))).decode());
        assertArrayEquals(new long[] {9, 9, 9}, EncodedColumn.fromBytes(Img.seal(Img.delta(3, 9, 0, new long[0]))).decode());
        assertArrayEquals(new long[] {1, 2}, EncodedColumn.fromBytes(Img.seal(Img.plain(1, 2))).decode());
    }

    @Test
    void craftedRleDefectsAreRejected() {
        rejected("zero-length run", Img.rle(3, new long[] {7, 8, 9}, new int[] {2, 0, 1}));
        rejected("runs sum below n", Img.rle(4, new long[] {7, 8}, new int[] {2, 1}));
        rejected("runs sum above n", Img.rle(2, new long[] {7, 8}, new int[] {2, 1}));
        rejected("negative run length", Img.rle(1, new long[] {7, 8}, new int[] {2, -1}));
    }

    @Test
    void craftedDictDefectsAreRejected() {
        rejected("dictionary unsorted", Img.dict(3, new long[] {20, 10}, 1, 0b010L));
        rejected("dictionary duplicate", Img.dict(3, new long[] {10, 10}, 1, 0b010L));
        rejected("id out of range", Img.dict(3, new long[] {10, 20, 30}, 2, 0b11_01_00L)); // id 3 >= size 3
        rejected("width not canonical", Img.dict(3, new long[] {10, 20}, 2, 0b01_00_00L));
        rejected("empty dictionary", Img.dict(3, new long[] {}, 0, 0L));
        rejected("padding bits set", Img.dict(3, new long[] {10, 20}, 1, 0b1000_010L));
    }

    @Test
    void craftedForAndDeltaDefectsAreRejected() {
        rejected("FOR width above 64", Img.frameOfReference(3, 5, 65, 0L));
        rejected("FOR width not canonical", Img.frameOfReference(3, 5, 3, 0b010_001_000L)); // max packed value 2 needs width 2
        rejected("FOR padding bits set", Img.frameOfReference(3, 5, 2, 0b10_01_00L | (1L << 40)));
        // offsets 1,2,3 are canonical at width 2 but then base 5 is not the minimum (6): rejected; control above has offset 0
        rejected("FOR base is not the minimum", Img.frameOfReference(3, 5, 2, 0b11_10_01L));
        rejected("DELTA width not canonical", Img.delta(3, 9, 3, new long[] {1, 2}));
        rejected("DELTA padding set", Img.delta(3, 9, 2, new long[] {1, 2}, 1L << 50));
    }

    @Test
    void craftedHeaderDefectsAreRejectedWithoutHugeAllocations() {
        rejected("unknown encoding byte", Img.header(1, 9));
        rejected("negative n", Img.header(-1, 0));
        rejected("zero n", Img.header(0, 0));
        rejected("huge n PLAIN", Img.header(Integer.MAX_VALUE, 0));
        rejected("huge n FOR", Img.concat(Img.header(Integer.MAX_VALUE, 3), Img.longBytes(0), new byte[] {1}));
        rejected("n above MAX_ROWS with width 0", Img.concat(Img.header(EncodedColumn.MAX_ROWS + 1, 3), Img.longBytes(0), new byte[] {0}));
        rejected("huge run count", Img.concat(Img.header(4, 1), Img.intBytes(Integer.MAX_VALUE)));
        rejected("huge dict size", Img.concat(Img.header(4, 2), Img.intBytes(Integer.MAX_VALUE)));
    }

    @Test
    void randomGarbageNeverEscapesAsAnotherExceptionType() {
        Random rnd = new Random(5);
        for (int i = 0; i < 2000; i++) {
            byte[] junk = new byte[rnd.nextInt(60)];
            rnd.nextBytes(junk);
            byte[] sealed = junk.length >= 4 && rnd.nextBoolean() ? Img.seal(junk) : junk;
            try {
                EncodedColumn.fromBytes(sealed).decode();
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
    }
}
