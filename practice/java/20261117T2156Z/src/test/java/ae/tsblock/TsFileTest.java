package ae.tsblock;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TsFileTest {
    @TempDir Path dir;

    private Path write(String name, int blockSize, List<Point> pts) throws IOException {
        Path p = dir.resolve(name);
        try (TsWriter w = TsWriter.create(p, blockSize)) {
            for (Point pt : pts) {
                w.append(pt.ts(), pt.value());
            }
        }
        return p;
    }

    private static List<Point> regular(int n) {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            pts.add(new Point(1_000_000L + 1000L * i, 50L + (i % 3)));
        }
        return pts;
    }

    @Test
    void roundTripSmall() throws IOException {
        List<Point> pts = List.of(new Point(10, 5), new Point(20, 7), new Point(35, -3));
        Path p = write("a.tsb", 2, pts);
        TsReader r = TsReader.open(p);
        assertEquals(3, r.count());
        assertEquals(pts, r.all());
        assertEquals(OptionalLong.of(10), r.minTs());
        assertEquals(OptionalLong.of(35), r.maxTs());
    }

    @Test
    void emptyFileIsValid() throws IOException {
        Path p = write("e.tsb", 4, List.of());
        TsReader r = TsReader.open(p);
        assertEquals(0, r.count());
        assertEquals(List.of(), r.all());
        assertEquals(OptionalLong.empty(), r.minTs());
        assertEquals(OptionalLong.empty(), r.maxTs());
        assertEquals(List.of(), r.range(Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    void extremeValuesRoundTrip() throws IOException {
        List<Point> pts = List.of(
                new Point(Long.MIN_VALUE, Long.MAX_VALUE),
                new Point(-1, Long.MIN_VALUE),
                new Point(0, 0),
                new Point(Long.MAX_VALUE - 1, Long.MAX_VALUE),
                new Point(Long.MAX_VALUE, Long.MIN_VALUE));
        for (int bs : new int[] {1, 2, 3, 100}) {
            Path p = write("x" + bs + ".tsb", bs, pts);
            assertEquals(pts, TsReader.open(p).all(), "blockSize " + bs);
        }
    }

    @Test
    void blockCountFollowsBlockSize() throws IOException {
        Path p = write("b.tsb", 10, regular(25));
        TsReader r = TsReader.open(p);
        assertEquals(3, r.blockCount());
        assertEquals(25, r.count());
    }

    @Test
    void rejectsNonIncreasingTimestamps() throws IOException {
        try (TsWriter w = TsWriter.create(dir.resolve("n.tsb"), 4)) {
            w.append(5, 1);
            assertThrows(IllegalArgumentException.class, () -> w.append(5, 2));
            assertThrows(IllegalArgumentException.class, () -> w.append(4, 2));
            w.append(6, 3); // writer still usable after a rejected append
        }
        assertEquals(List.of(new Point(5, 1), new Point(6, 3)), TsReader.open(dir.resolve("n.tsb")).all());
    }

    @Test
    void rejectsBadBlockSize() {
        assertThrows(IllegalArgumentException.class, () -> TsWriter.create(dir.resolve("z.tsb"), 0));
    }

    @Test
    void rangeMatchesBruteForce() throws IOException {
        Random rnd = new Random(42);
        List<Point> pts = new ArrayList<>();
        long ts = -500;
        for (int i = 0; i < 300; i++) {
            ts += 1 + rnd.nextInt(20);
            pts.add(new Point(ts, rnd.nextLong() % 1000));
        }
        Path p = write("r.tsb", 7, pts);
        TsReader r = TsReader.open(p);
        for (int t = 0; t < 200; t++) {
            long a = -600 + rnd.nextInt(3500);
            long b = a + rnd.nextInt(400) - 50; // may be <= a
            List<Point> expected = pts.stream().filter(x -> x.ts() >= a && x.ts() < b).toList();
            assertEquals(expected, r.range(a, b), "range [" + a + "," + b + ")");
        }
    }

    @Test
    void rangeBoundariesAreHalfOpen() throws IOException {
        Path p = write("h.tsb", 2, List.of(new Point(10, 1), new Point(20, 2), new Point(30, 3)));
        TsReader r = TsReader.open(p);
        assertEquals(List.of(new Point(20, 2)), r.range(20, 30));
        assertEquals(List.of(new Point(20, 2), new Point(30, 3)), r.range(20, 31));
        assertEquals(List.of(), r.range(20, 20));
        assertEquals(List.of(), r.range(31, 100));
    }

    @Test
    void rangeDecodesOnlyOverlappingBlocks() throws IOException {
        // 100 points, ts = 0,10,...,990, block size 10 => 10 blocks of 100 ts-units each
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pts.add(new Point(i * 10L, i));
        }
        TsReader r = TsReader.open(write("s.tsb", 10, pts));
        assertEquals(0, r.blocksDecoded());
        assertEquals(10, r.range(300, 400).size());
        assertEquals(1, r.blocksDecoded());
        r.range(385, 405); // straddles blocks 3 and 4
        assertEquals(3, r.blocksDecoded());
        r.range(2000, 3000); // beyond the data
        assertEquals(3, r.blocksDecoded());
    }

    @Test
    void regularDataCompressesWell() throws IOException {
        Path p = write("c.tsb", 256, regular(10_000));
        long size = Files.size(p);
        // naive layout would be 16 bytes per point = 160_000
        assertTrue(size < 25_000, "size was " + size);
    }

    @Test
    void nothingAtTargetUntilClose() throws IOException {
        Path target = dir.resolve("t.tsb");
        TsWriter w = TsWriter.create(target, 2);
        w.append(1, 1);
        w.append(2, 2);
        w.append(3, 3);
        assertFalse(Files.exists(target));
        w.close();
        assertTrue(Files.exists(target));
        assertEquals(1, listFiles().size(), "no temp files remain: " + listFiles());
        w.close(); // idempotent
        assertEquals(3, TsReader.open(target).count());
    }

    @Test
    void abortLeavesNothingAndKeepsExistingTarget() throws IOException {
        Path target = write("old.tsb", 2, List.of(new Point(1, 1)));
        TsWriter w = TsWriter.create(target, 2);
        w.append(9, 9);
        w.abort();
        assertEquals(List.of(new Point(1, 1)), TsReader.open(target).all());
        assertEquals(1, listFiles().size(), listFiles().toString());
        assertThrows(IllegalStateException.class, () -> w.append(10, 10));
    }

    @Test
    void closeOverwritesExistingTarget() throws IOException {
        Path target = write("o.tsb", 2, List.of(new Point(1, 1)));
        write("o.tsb", 2, List.of(new Point(7, 7), new Point(8, 8)));
        assertEquals(List.of(new Point(7, 7), new Point(8, 8)), TsReader.open(target).all());
    }

    @Test
    void appendAfterCloseFails() throws IOException {
        TsWriter w = TsWriter.create(dir.resolve("ac.tsb"), 2);
        w.close();
        assertThrows(IllegalStateException.class, () -> w.append(1, 1));
    }

    // ---- corruption ----

    @Test
    void wrongMagicRejected() throws IOException {
        Path p = write("m.tsb", 4, regular(10));
        byte[] b = Files.readAllBytes(p);
        b[0] ^= 0x01;
        Files.write(p, b);
        assertThrows(CorruptFileException.class, () -> TsReader.open(p));
    }

    @Test
    void everyTruncationIsRejected() throws IOException {
        Path p = write("tr.tsb", 4, regular(10));
        byte[] b = Files.readAllBytes(p);
        for (int len = 0; len < b.length; len++) {
            Path q = dir.resolve("trunc" + len);
            Files.write(q, java.util.Arrays.copyOf(b, len));
            final int l = len;
            assertThrows(CorruptFileException.class, () -> TsReader.open(q), "length " + l);
        }
    }

    @Test
    void anySingleByteFlipIsDetectedOnOpenOrRead() throws IOException {
        Path p = write("f.tsb", 4, regular(10));
        byte[] orig = Files.readAllBytes(p);
        List<Point> expected = regular(10);
        for (int i = 0; i < orig.length; i++) {
            byte[] b = orig.clone();
            b[i] ^= 0x10;
            Path q = dir.resolve("flip");
            Files.write(q, b);
            final int idx = i;
            boolean detected;
            try {
                detected = !TsReader.open(q).all().equals(expected);
                // A flip that is silently accepted must never yield different data without an exception.
                assertFalse(detected, "silent wrong data at byte " + idx);
            } catch (CorruptFileException e) {
                // expected for every byte position
            }
        }
    }

    @Test
    void flipInBlockOnlyFailsThatBlockWhenQueried() throws IOException {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            pts.add(new Point(i * 10L, i));
        }
        Path p = write("fb.tsb", 10, pts);
        byte[] b = Files.readAllBytes(p);
        // first block payload starts at 4 (magic) + 8 (len+crc)
        b[4 + 8 + 2] ^= 0x04;
        Files.write(p, b);
        TsReader r = TsReader.open(p);
        assertThrows(CorruptFileException.class, () -> r.range(0, 100));
        assertEquals(10, r.range(100, 200).size()); // other blocks are fine
    }

    private List<Path> listFiles() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }
}
