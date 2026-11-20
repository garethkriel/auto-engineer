package ae.tsdb;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class TimeSeriesDbTest {
    private static TimeSeriesDb filled(int blockSize, int n) {
        TimeSeriesDb db = new TimeSeriesDb(blockSize);
        for (int i = 0; i < n; i++) db.append("cpu", 1000L + i * 10L, i);
        return db;
    }

    @Test
    void appendAndQueryAcrossBlocksAndHead() {
        TimeSeriesDb db = filled(4, 10); // blocks: 0-3, 4-7 ; head 8-9
        assertEquals(10, db.pointCount("cpu"));
        List<Point> all = db.query("cpu", 0, Long.MAX_VALUE);
        assertEquals(10, all.size());
        assertEquals(new Point(1000, 0), all.get(0));
        assertEquals(new Point(1090, 9), all.get(9));
        // half-open range
        List<Point> mid = db.query("cpu", 1030, 1070);
        assertEquals(List.of(new Point(1030, 3), new Point(1040, 4), new Point(1050, 5), new Point(1060, 6)), mid);
        assertEquals(List.of(), db.query("cpu", 1031, 1039));
        assertEquals(List.of(), db.query("nope", 0, 100));
        assertEquals(List.of(), db.query("cpu", 50, 50));
    }

    @Test
    void sealedBlocksAreCompressedAndHeadIsNot() {
        TimeSeriesDb db = filled(8, 20); // 2 sealed + head of 4
        assertEquals(2, db.blockCount("cpu"));
        assertTrue(db.compressedBytes("cpu") > 0);
        assertEquals(0, new TimeSeriesDb(8).compressedBytes("cpu"));
    }

    @Test
    void outOfOrderAndDuplicateAppendsRejectedWithoutSideEffects() {
        TimeSeriesDb db = filled(3, 5);
        assertThrows(IllegalArgumentException.class, () -> db.append("cpu", 1040, 1)); // equals last ts
        assertThrows(IllegalArgumentException.class, () -> db.append("cpu", 10, 1));
        assertThrows(IllegalArgumentException.class, () -> db.append("cpu", -5, 1));
        assertThrows(IllegalArgumentException.class, () -> db.append("", 5, 1));
        assertThrows(NullPointerException.class, () -> db.append(null, 5, 1));
        assertEquals(5, db.pointCount("cpu"));
        assertEquals(Optional.of(new Point(1040, 4)), db.latest("cpu"));
        db.append("cpu", 1041, 9); // still works afterwards
        assertEquals(Optional.of(new Point(1041, 9)), db.latest("cpu"));
        assertEquals(Optional.empty(), db.latest("other"));
    }

    @Test
    void seriesAreIndependent() {
        TimeSeriesDb db = new TimeSeriesDb(2);
        db.append("a", 10, 1);
        db.append("b", 5, 2); // earlier ts than a's is fine
        db.append("a", 11, 3);
        assertEquals(List.of("a", "b"), db.seriesNames());
        assertEquals(2, db.pointCount("a"));
        assertEquals(1, db.pointCount("b"));
    }

    @Test
    void randomisedQueriesMatchNaiveModel() {
        Random rnd = new Random(99);
        for (int round = 0; round < 40; round++) {
            int bs = 1 + rnd.nextInt(9);
            TimeSeriesDb db = new TimeSeriesDb(bs);
            List<Point> model = new ArrayList<>();
            long ts = rnd.nextInt(50);
            for (int i = 0; i < rnd.nextInt(80); i++) {
                ts += 1 + rnd.nextInt(30);
                Point p = new Point(ts, rnd.nextBoolean() ? rnd.nextGaussian() : (double) i);
                db.append("s", p.ts(), p.value());
                model.add(p);
            }
            for (int q = 0; q < 30; q++) {
                long from = rnd.nextInt((int) ts + 20);
                long to = from + rnd.nextInt(200);
                List<Point> expect = new ArrayList<>();
                for (Point p : model) if (p.ts() >= from && p.ts() < to) expect.add(p);
                assertEquals(expect, db.query("s", from, to), "bs=" + bs + " [" + from + "," + to + ")");
            }
        }
    }

    @Test
    void queryDecodesOnlyOverlappingBlocks() {
        TimeSeriesDb db = filled(4, 40); // 10 sealed blocks
        db.resetDecodeCount();
        db.query("cpu", 1000, 1040); // exactly block 0
        assertEquals(1, db.blocksDecoded());
        db.resetDecodeCount();
        db.query("cpu", 1025, 1045); // straddles blocks 0 (max 1030) and 1 (min 1040)
        assertEquals(2, db.blocksDecoded());
        db.resetDecodeCount();
        db.query("cpu", 5000, 6000);
        assertEquals(0, db.blocksDecoded());
    }

    @Test
    void aggregatesAlignedToFromAndSkipEmptyBuckets() {
        TimeSeriesDb db = new TimeSeriesDb(3);
        // ts: 100..: values
        long[] ts = {100, 101, 109, 110, 135, 136, 137};
        double[] v = {1, 5, 3, 10, 7, Double.NaN, 2};
        for (int i = 0; i < ts.length; i++) db.append("m", ts[i], v[i]);
        // buckets of 10 from 100: [100,110)={1,5,3}, [110,120)={10}, [130,140)={7,NaN,2} -> NaN ignored
        assertEquals(List.of(new Bucket(100, 3, 9.0), new Bucket(110, 1, 10.0), new Bucket(130, 2, 9.0)),
                db.aggregate("m", 100, 140, 10, Agg.SUM));
        assertEquals(List.of(new Bucket(100, 3, 3.0), new Bucket(110, 1, 10.0), new Bucket(130, 2, 4.5)),
                db.aggregate("m", 100, 140, 10, Agg.AVG));
        assertEquals(List.of(new Bucket(100, 3, 1.0), new Bucket(110, 1, 10.0), new Bucket(130, 2, 2.0)),
                db.aggregate("m", 100, 140, 10, Agg.MIN));
        assertEquals(List.of(new Bucket(100, 3, 5.0), new Bucket(110, 1, 10.0), new Bucket(130, 2, 7.0)),
                db.aggregate("m", 100, 140, 10, Agg.MAX));
        assertEquals(List.of(new Bucket(100, 3, 1.0), new Bucket(110, 1, 10.0), new Bucket(130, 2, 7.0)),
                db.aggregate("m", 100, 140, 10, Agg.FIRST));
        assertEquals(List.of(new Bucket(100, 3, 3.0), new Bucket(110, 1, 10.0), new Bucket(130, 2, 2.0)),
                db.aggregate("m", 100, 140, 10, Agg.LAST));
        assertEquals(List.of(new Bucket(100, 3, 3.0), new Bucket(110, 1, 1.0), new Bucket(130, 2, 2.0)),
                db.aggregate("m", 100, 140, 10, Agg.COUNT));
        // from not a multiple of the series start: buckets align to from=103 -> [103,113),[113,123)...
        assertEquals(List.of(new Bucket(103, 2, 13.0), new Bucket(133, 2, 9.0)),
                db.aggregate("m", 103, 143, 10, Agg.SUM));
        // `to` truncates the last bucket
        assertEquals(List.of(new Bucket(130, 1, 7.0)), db.aggregate("m", 130, 136, 10, Agg.SUM));
        assertThrows(IllegalArgumentException.class, () -> db.aggregate("m", 0, 10, 0, Agg.SUM));
        assertEquals(List.of(), db.aggregate("zzz", 0, 10, 5, Agg.SUM));
    }

    @Test
    void aggregateMatchesNaiveOracleOnRandomData() {
        Random rnd = new Random(5);
        TimeSeriesDb db = new TimeSeriesDb(7);
        List<Point> model = new ArrayList<>();
        long ts = 0;
        for (int i = 0; i < 300; i++) {
            ts += 1 + rnd.nextInt(9);
            Point p = new Point(ts, rnd.nextInt(100) - 50);
            db.append("x", p.ts(), p.value());
            model.add(p);
        }
        long from = 37, to = ts - 11, step = 25;
        List<Bucket> got = db.aggregate("x", from, to, step, Agg.MAX);
        List<Bucket> expect = new ArrayList<>();
        for (long b = from; b < to; b += step) {
            double max = Double.NEGATIVE_INFINITY;
            int n = 0;
            for (Point p : model) {
                if (p.ts() >= b && p.ts() < Math.min(b + step, to)) {
                    max = Math.max(max, p.value());
                    n++;
                }
            }
            if (n > 0) expect.add(new Bucket(b, n, max));
        }
        assertEquals(expect, got);
    }

    @Test
    void retainDropsOnlyWholeSealedBlocksBelowCutoff() {
        TimeSeriesDb db = filled(4, 10); // blocks [1000..1030], [1040..1070]; head 1080,1090
        assertEquals(0, db.retain(1000));
        assertEquals(0, db.retain(1030)); // block 0's max is 1030, which is not < 1030
        assertEquals(4, db.retain(1031));
        assertEquals(6, db.pointCount("cpu"));
        assertEquals(1040, db.query("cpu", 0, Long.MAX_VALUE).get(0).ts());
        assertEquals(0, db.retain(1070)); // block 1 max is 1070, not < 1070: kept whole
        assertEquals(0, db.retain(1050)); // cutoff inside block 1: kept whole
        assertEquals(4, db.retain(1500)); // drops block 1; head is never dropped
        assertEquals(2, db.pointCount("cpu"));
        assertEquals(0, db.blockCount("cpu"));
        db.append("cpu", 1100, 1); // series still usable
        assertEquals(3, db.pointCount("cpu"));
    }

    @Test
    void retainAppliesToEverySeries() {
        TimeSeriesDb db = new TimeSeriesDb(2);
        for (int i = 0; i < 4; i++) {
            db.append("a", i, i);
            db.append("b", 100 + i, i);
        }
        assertEquals(4, db.retain(50)); // a has 2 sealed blocks (4 points), b has none below 50
        assertEquals(0, db.pointCount("a"));
        assertEquals(4, db.pointCount("b"));
    }

    @Test
    void compressionBeatsRawForRegularMetrics() {
        TimeSeriesDb db = new TimeSeriesDb(120);
        for (int i = 0; i < 1200; i++) db.append("m", 1_700_000_000_000L + 15_000L * i, 100 + (i % 3) * 0.5);
        assertEquals(10, db.blockCount("m"));
        long raw = 1200L * 16;
        assertTrue(db.compressedBytes("m") * 8 < raw, "compressed " + db.compressedBytes("m") + " raw " + raw);
    }

    // ---------------- snapshots ----------------

    private static TimeSeriesDb sample() {
        TimeSeriesDb db = new TimeSeriesDb(5);
        for (int i = 0; i < 13; i++) db.append("temp", 10L * i, Math.sqrt(i));
        for (int i = 0; i < 7; i++) db.append("alpha/β", 3L * i + 1, i % 2 == 0 ? Double.NaN : -0.0);
        db.append("empty-after-retain", 1, 1);
        return db;
    }

    @Test
    void snapshotRoundTripsAndIsCanonical() {
        TimeSeriesDb db = sample();
        byte[] img = db.snapshot();
        TimeSeriesDb back = TimeSeriesDb.restore(img);
        assertEquals(db.seriesNames(), back.seriesNames());
        for (String s : db.seriesNames()) {
            assertEquals(db.query(s, 0, Long.MAX_VALUE).size(), back.query(s, 0, Long.MAX_VALUE).size());
            List<Point> a = db.query(s, 0, Long.MAX_VALUE), b = back.query(s, 0, Long.MAX_VALUE);
            for (int i = 0; i < a.size(); i++) {
                assertEquals(a.get(i).ts(), b.get(i).ts());
                assertEquals(Double.doubleToRawLongBits(a.get(i).value()), Double.doubleToRawLongBits(b.get(i).value()));
            }
            assertEquals(db.blockCount(s), back.blockCount(s));
        }
        assertArrayEquals(img, back.snapshot());
        // restored db keeps working with the same block size
        back.append("temp", 1000, 1);
        assertEquals(14, back.pointCount("temp"));
        // insertion order of series does not change the image
        TimeSeriesDb x = new TimeSeriesDb(5), y = new TimeSeriesDb(5);
        x.append("a", 1, 1); x.append("b", 1, 2);
        y.append("b", 1, 2); y.append("a", 1, 1);
        assertArrayEquals(x.snapshot(), y.snapshot());
    }

    @Test
    void emptyDatabaseSnapshot() {
        TimeSeriesDb back = TimeSeriesDb.restore(new TimeSeriesDb(3).snapshot());
        assertEquals(List.of(), back.seriesNames());
    }

    @Test
    void everyTruncationIsRejected() {
        byte[] img = sample().snapshot();
        for (int cut = 0; cut < img.length; cut++) {
            final int c = cut;
            assertThrows(CorruptDataException.class, () -> TimeSeriesDb.restore(Arrays.copyOf(img, c)), "cut " + c);
        }
    }

    @Test
    void everyBitFlipIsRejected() {
        byte[] img = sample().snapshot();
        for (int i = 0; i < img.length; i++) {
            for (int bit = 0; bit < 8; bit += 3) {
                byte[] c = img.clone();
                c[i] ^= (byte) (1 << bit);
                assertThrows(CorruptDataException.class, () -> TimeSeriesDb.restore(c), "byte " + i + " bit " + bit);
            }
        }
    }

    private static byte[] reseal(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        return ByteBuffer.allocate(body.length + 4).put(body).putInt((int) crc.getValue()).array();
    }

    @Test
    void validCrcButBadStructureIsRejected() {
        TimeSeriesDb db = new TimeSeriesDb(2);
        db.append("s", 10, 1);
        db.append("s", 20, 2); // one sealed block, empty head
        byte[] img = db.snapshot();
        byte[] body = Arrays.copyOf(img, img.length - 4);
        assertDoesNotThrow(() -> TimeSeriesDb.restore(reseal(body))); // control: resealing alone is harmless
        // locate the block's min ts (10) and rewrite it to 11: header declares min 10 but data says 10 -> mismatch
        byte[] bad = body.clone();
        int pos = indexOfLong(bad, 10L);
        assertTrue(pos >= 0);
        ByteBuffer.wrap(bad).putLong(pos, 11L);
        CorruptDataException e = assertThrows(CorruptDataException.class, () -> TimeSeriesDb.restore(reseal(bad)));
        assertTrue(e.getMessage().contains("min"), e.getMessage());
        // wrong version
        byte[] ver = body.clone();
        ver[4] = 99;
        assertTrue(assertThrows(CorruptDataException.class, () -> TimeSeriesDb.restore(reseal(ver)))
                .getMessage().contains("version"));
        // bad magic
        byte[] magic = body.clone();
        magic[0] = 'X';
        assertTrue(assertThrows(CorruptDataException.class, () -> TimeSeriesDb.restore(reseal(magic)))
                .getMessage().contains("magic"));
    }

    private static int indexOfLong(byte[] b, long v) {
        for (int i = 0; i + 8 <= b.length; i++) if (ByteBuffer.wrap(b).getLong(i) == v) return i;
        return -1;
    }

    @Test
    void headOverlappingLastBlockIsRejected() {
        // hand-build an image: one series, blockSize 4, one block [10,20], head point at ts 15
        byte[] block = GorillaCodec.encode(List.of(new Point(10, 1), new Point(20, 2)));
        ByteBuffer bb = ByteBuffer.allocate(256);
        bb.put(new byte[] {'T', 'S', 'D', 'B'}).put((byte) 1).putInt(4).putInt(1);
        byte[] name = {'s'};
        bb.putShort((short) name.length).put(name);
        bb.putInt(1); // blocks
        bb.putInt(2).putLong(10).putLong(20).putInt(block.length).put(block);
        bb.putInt(1).putLong(15).putLong(Double.doubleToRawLongBits(3.0)); // head
        byte[] body = Arrays.copyOf(bb.array(), bb.position());
        CorruptDataException e = assertThrows(CorruptDataException.class, () -> TimeSeriesDb.restore(reseal(body)));
        assertTrue(e.getMessage().contains("order"), e.getMessage());
        // control: same image with head ts 21 loads
        ByteBuffer ok = ByteBuffer.wrap(body.clone());
        ok.putLong(body.length - 16, 21L);
        assertEquals(3, TimeSeriesDb.restore(reseal(ok.array())).pointCount("s"));
    }
}
