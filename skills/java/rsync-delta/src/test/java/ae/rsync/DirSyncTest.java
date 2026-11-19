package ae.rsync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirSyncTest {
    @TempDir
    Path tmp;

    private Path src() throws IOException {
        return Files.createDirectories(tmp.resolve("src"));
    }

    private Path dst() throws IOException {
        return Files.createDirectories(tmp.resolve("dst"));
    }

    private static void put(Path root, String rel, byte[] data) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
    }

    /** Relative "/"-joined path -> content, for regular files only. */
    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    StringBuilder key = new StringBuilder();
                    for (Path name : root.relativize(p)) {
                        if (key.length() > 0) {
                            key.append('/');
                        }
                        key.append(name);
                    }
                    m.put(key.toString(), Files.readAllBytes(p));
                }
            }
        }
        return m;
    }

    private static void assertSameTree(Path a, Path b) throws IOException {
        Map<String, byte[]> x = snapshot(a);
        Map<String, byte[]> y = snapshot(b);
        assertEquals(x.keySet(), y.keySet());
        for (String k : x.keySet()) {
            assertArrayEquals(x.get(k), y.get(k), k);
        }
    }

    @Test
    void freshCopyCreatesEverythingAndSendsAllBytesAsLiterals() throws Exception {
        Path s = src();
        put(s, "a.bin", TestData.random(1, 1000));
        put(s, "dir/b.bin", TestData.random(2, 500));
        put(s, "dir/deep/c.bin", new byte[0]);
        Path d = tmp.resolve("dst-new"); // does not exist yet
        DirSync.Report r = DirSync.sync(s, d, 64);
        assertSameTree(s, d);
        assertEquals(3, r.created());
        assertEquals(0, r.updated());
        assertEquals(0, r.unchanged());
        assertEquals(0, r.deleted());
        assertEquals(1500, r.sourceBytes());
        assertEquals(1500, r.literalBytes());
    }

    @Test
    void smallEditInLargeFileTransfersLittle() throws Exception {
        Path s = src();
        Path d = dst();
        byte[] big = TestData.random(3, 100_000);
        put(d, "big.bin", big);
        put(d, "same.txt", TestData.ascii("hello"));
        byte[] edited = TestData.concat(TestData.slice(big, 0, 40_000), TestData.ascii("INSERTED"), TestData.slice(big, 40_000, big.length));
        put(s, "big.bin", edited);
        put(s, "same.txt", TestData.ascii("hello"));
        DirSync.Report r = DirSync.sync(s, d, 256);
        assertSameTree(s, d);
        assertEquals(1, r.updated());
        assertEquals(1, r.unchanged());
        assertEquals(0, r.created());
        assertTrue(r.literalBytes() >= 8 && r.literalBytes() <= 8 + 256, "literals " + r.literalBytes());
        assertTrue(r.signatureBytes() + r.deltaBytes() < edited.length / 4, "wire " + (r.signatureBytes() + r.deltaBytes()));
    }

    @Test
    void extraFilesAndEmptiedDirectoriesAreRemoved() throws Exception {
        Path s = src();
        Path d = dst();
        put(s, "keep.txt", TestData.ascii("k"));
        put(d, "keep.txt", TestData.ascii("k"));
        put(d, "gone/x/y.txt", TestData.ascii("y"));
        put(d, "gone.txt", TestData.ascii("g"));
        DirSync.Report r = DirSync.sync(s, d, 8);
        assertEquals(2, r.deleted());
        assertFalse(Files.exists(d.resolve("gone")), "emptied directories are pruned");
        assertSameTree(s, d);
    }

    @Test
    void secondRunIsIdempotentAndTouchesNothing() throws Exception {
        Path s = src();
        Path d = dst();
        put(s, "a/one.bin", TestData.random(4, 3000));
        put(s, "two.bin", TestData.random(5, 10));
        DirSync.sync(s, d, 128);
        FileTime stamp = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(d.resolve("a/one.bin"), stamp);
        DirSync.Report again = DirSync.sync(s, d, 128);
        assertEquals(2, again.unchanged());
        assertEquals(0, again.created() + again.updated() + again.deleted());
        assertEquals(0, again.literalBytes());
        assertEquals(stamp, Files.getLastModifiedTime(d.resolve("a/one.bin")), "unchanged file must not be rewritten");
    }

    @Test
    void noTempFilesRemain() throws Exception {
        Path s = src();
        Path d = dst();
        put(s, "a.bin", TestData.random(6, 5000));
        put(s, "sub/b.bin", TestData.random(7, 5000));
        put(d, "a.bin", TestData.random(8, 4000));
        DirSync.sync(s, d, 100);
        assertEquals(snapshot(s).keySet(), snapshot(d).keySet());
        try (Stream<Path> all = Files.walk(d)) {
            long files = all.filter(Files::isRegularFile).count();
            assertEquals(2, files);
        }
    }

    @Test
    void fileAndDirectoryTypeConflictsAreResolved() throws Exception {
        Path s = src();
        Path d = dst();
        put(s, "x", TestData.ascii("now a file"));        // dst has x/ as directory
        put(s, "y/inner.txt", TestData.ascii("inner"));   // dst has y as a file
        put(d, "x/old.txt", TestData.ascii("old"));
        put(d, "y", TestData.ascii("was a file"));
        DirSync.sync(s, d, 4);
        assertSameTree(s, d);
    }

    @Test
    void rejectsNestedOrIdenticalRoots() throws Exception {
        Path s = src();
        put(s, "f", TestData.ascii("f"));
        assertThrows(IllegalArgumentException.class, () -> DirSync.sync(s, s, 8));
        assertThrows(IllegalArgumentException.class, () -> DirSync.sync(s, s.resolve("inside"), 8));
        assertThrows(IllegalArgumentException.class, () -> DirSync.sync(s.resolve("f"), tmp.resolve("o"), 8));
        assertThrows(IllegalArgumentException.class, () -> DirSync.sync(s, tmp.resolve("o"), 0));
        Path parent = tmp;
        assertThrows(IllegalArgumentException.class, () -> DirSync.sync(s, parent, 8), "dst containing src");
        assertTrue(Files.exists(s.resolve("f")), "source must never be damaged");
    }

    @Test
    void randomTreesConverge() throws Exception {
        Random r = new Random(99);
        for (int round = 0; round < 25; round++) {
            Path root = Files.createDirectories(tmp.resolve("r" + round));
            Path s = Files.createDirectories(root.resolve("s"));
            Path d = Files.createDirectories(root.resolve("d"));
            String[] names = {"a", "b.txt", "d1/c", "d1/d2/e", "d3/f", "g"};
            for (String n : names) {
                if (r.nextInt(3) > 0) {
                    byte[] base = TestData.random(r.nextLong(), r.nextInt(3000));
                    put(d, n, base);
                    if (r.nextBoolean()) {
                        int at = r.nextInt(base.length + 1);
                        put(s, n, TestData.concat(TestData.slice(base, 0, at), TestData.random(r.nextLong(), r.nextInt(40)),
                                TestData.slice(base, at, base.length)));
                    }
                }
                if (r.nextInt(3) == 0 && !Files.exists(s.resolve(n)) && !Files.isDirectory(d.resolve(n))) {
                    put(s, n, TestData.random(r.nextLong(), r.nextInt(500)));
                }
            }
            int block = new int[] {1, 7, 64, 512}[r.nextInt(4)];
            DirSync.sync(s, d, block);
            assertSameTree(s, d);
            DirSync.Report again = DirSync.sync(s, d, block);
            assertEquals(0, again.created() + again.updated() + again.deleted(), "round " + round);
            assertEquals(0, again.literalBytes());
        }
    }
}
