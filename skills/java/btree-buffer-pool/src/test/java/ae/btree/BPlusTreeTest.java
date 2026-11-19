package ae.btree;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BPlusTreeTest {
    @TempDir Path dir;

    private BPlusTree open(String name, int page, int frames) throws IOException {
        return BPlusTree.open(dir.resolve(name), page, frames);
    }

    @Test
    void emptyTree() throws IOException {
        try (BPlusTree t = open("a.db", 64, 8)) {
            assertEquals(0, t.size());
            assertTrue(t.get(1).isEmpty());
            assertTrue(t.remove(1).isEmpty());
            assertTrue(t.range(Long.MIN_VALUE, Long.MAX_VALUE).isEmpty());
            t.check();
        }
    }

    @Test
    void putGetReplace() throws IOException {
        try (BPlusTree t = open("b.db", 64, 8)) {
            assertTrue(t.put(5, 50).isEmpty());
            assertEquals(Optional.of(50L), t.put(5, 51));
            assertEquals(Optional.of(51L), t.get(5));
            assertEquals(1, t.size());
        }
    }

    @Test
    void ascendingInsertBuildsMultiLevelTree() throws IOException {
        try (BPlusTree t = open("c.db", 64, 16)) {
            for (long k = 1; k <= 200; k++) {
                t.put(k, k * 10);
                t.check();
            }
            assertEquals(200, t.size());
            assertTrue(t.height() >= 3, "height " + t.height());
            for (long k = 1; k <= 200; k++) assertEquals(Optional.of(k * 10), t.get(k));
            assertTrue(t.get(0).isEmpty());
            assertTrue(t.get(201).isEmpty());
        }
    }

    @Test
    void descendingInsertAndNegativeKeys() throws IOException {
        try (BPlusTree t = open("d.db", 64, 16)) {
            for (long k = 100; k >= -100; k--) {
                t.put(k, k);
                t.check();
            }
            List<BPlusTree.Entry> all = t.range(Long.MIN_VALUE, Long.MAX_VALUE);
            assertEquals(201, all.size());
            assertEquals(-100, all.get(0).key());
            assertEquals(100, all.get(200).key());
        }
    }

    @Test
    void rangeBoundsAreInclusiveAndSorted() throws IOException {
        try (BPlusTree t = open("e.db", 64, 16)) {
            for (long k = 0; k < 100; k += 2) t.put(k, k);
            List<BPlusTree.Entry> r = t.range(10, 20);
            assertEquals(List.of(10L, 12L, 14L, 16L, 18L, 20L),
                    r.stream().map(BPlusTree.Entry::key).toList());
            assertEquals(List.of(12L, 14L), t.range(11, 15).stream().map(BPlusTree.Entry::key).toList());
            assertTrue(t.range(21, 21).isEmpty());
            assertTrue(t.range(30, 10).isEmpty(), "inverted range is empty");
            assertEquals(1, t.range(98, 1000).size());
        }
    }

    @Test
    void deleteAllShrinksHeightAndEmptiesTree() throws IOException {
        try (BPlusTree t = open("f.db", 64, 16)) {
            for (long k = 0; k < 150; k++) t.put(k, k);
            int h = t.height();
            assertTrue(h >= 3);
            for (long k = 0; k < 150; k++) {
                assertEquals(Optional.of(k), t.remove(k));
                t.check();
            }
            assertEquals(0, t.size());
            assertEquals(0, t.height());
            assertTrue(t.range(Long.MIN_VALUE, Long.MAX_VALUE).isEmpty());
            t.put(1, 1);
            assertEquals(Optional.of(1L), t.get(1));
            t.check();
        }
    }

    @Test
    void freedPagesAreReused() throws IOException {
        try (BPlusTree t = open("g.db", 64, 16)) {
            for (long k = 0; k < 300; k++) t.put(k, k);
            int pagesFull = t.pageCount();
            for (long k = 0; k < 300; k++) t.remove(k);
            for (long k = 0; k < 300; k++) t.put(k, k);
            t.check();
            assertEquals(pagesFull, t.pageCount(), "refill must reuse freed pages");
        }
    }

    @Test
    void randomDifferentialAgainstTreeMap() throws IOException {
        for (int seed = 0; seed < 6; seed++) {
            Random rnd = new Random(seed);
            TreeMap<Long, Long> model = new TreeMap<>();
            int page = seed % 2 == 0 ? 64 : 96;
            try (BPlusTree t = open("r" + seed + ".db", page, 12)) {
                for (int i = 0; i < 1500; i++) {
                    long k = rnd.nextInt(300) - 100;
                    int op = rnd.nextInt(10);
                    if (op < 5) {
                        long v = rnd.nextLong();
                        assertEquals(Optional.ofNullable(model.put(k, v)), t.put(k, v), "put " + k);
                    } else if (op < 8) {
                        assertEquals(Optional.ofNullable(model.remove(k)), t.remove(k), "remove " + k);
                    } else if (op == 8) {
                        assertEquals(Optional.ofNullable(model.get(k)), t.get(k));
                    } else {
                        long hi = k + rnd.nextInt(60);
                        List<BPlusTree.Entry> expect = new ArrayList<>();
                        for (Map.Entry<Long, Long> e : model.subMap(k, true, hi, true).entrySet()) {
                            expect.add(new BPlusTree.Entry(e.getKey(), e.getValue()));
                        }
                        assertEquals(expect, t.range(k, hi));
                    }
                    t.check();
                    assertEquals(model.size(), t.size());
                }
            }
        }
    }

    @Test
    void tinyPoolGivesSameAnswersAndEvicts() throws IOException {
        Random rnd = new Random(7);
        TreeMap<Long, Long> model = new TreeMap<>();
        try (BPlusTree t = open("h.db", 96, 8)) { // height <= 5 here, pool needs height+3 = 8
            for (int i = 0; i < 400; i++) {
                long k = rnd.nextInt(1000);
                model.put(k, k + 1);
                t.put(k, k + 1);
            }
            for (long k : model.keySet()) assertEquals(Optional.of(model.get(k)), t.get(k));
            assertTrue(t.physicalReads() > 0, "an 8-frame pool must miss on a larger tree");
            t.check();
        }
    }

    @Test
    void persistsAcrossReopen() throws IOException {
        Path file = dir.resolve("p.db");
        try (BPlusTree t = BPlusTree.open(file, 64, 8)) {
            for (long k = 0; k < 120; k++) t.put(k, -k);
            t.remove(7);
        }
        try (BPlusTree t = BPlusTree.open(file, 64, 8)) {
            assertEquals(119, t.size());
            assertTrue(t.get(7).isEmpty());
            assertEquals(Optional.of(-50L), t.get(50));
            t.check();
            t.put(7, 70);
        }
        try (BPlusTree t = BPlusTree.open(file, 64, 8)) {
            assertEquals(Optional.of(70L), t.get(7));
            assertEquals(120, t.size());
        }
    }

    @Test
    void reopenWithDifferentPageSizeRejected() throws IOException {
        Path file = dir.resolve("q.db");
        try (BPlusTree t = BPlusTree.open(file, 64, 8)) {
            t.put(1, 1);
        }
        IOException e = assertThrows(IOException.class, () -> BPlusTree.open(file, 128, 8));
        assertTrue(e.getMessage().contains("page size"), e.getMessage());
    }

    @Test
    void badMagicRejected() throws IOException {
        Path file = dir.resolve("m.db");
        try (BPlusTree t = BPlusTree.open(file, 64, 8)) {
            t.put(1, 1);
        }
        byte[] bytes = Files.readAllBytes(file);
        bytes[0] ^= 0x55;
        Files.write(file, bytes);
        IOException e = assertThrows(IOException.class, () -> BPlusTree.open(file, 64, 8));
        assertTrue(e.getMessage().contains("magic"), e.getMessage());
    }

    @Test
    void checkDetectsCorruptedLeafOrder() throws IOException {
        Path file = dir.resolve("x.db");
        try (BPlusTree t = BPlusTree.open(file, 64, 8)) {
            for (long k = 0; k < 3; k++) t.put(k, k);
        }
        // single leaf is page 2 (meta=0, ...): locate by scanning; swap the first two keys' bytes
        byte[] bytes = Files.readAllBytes(file);
        int leafOff = -1;
        for (int p = 1; p * 64 < bytes.length; p++) if (bytes[p * 64] == 1) leafOff = p * 64;
        assertTrue(leafOff > 0);
        int k0 = leafOff + 11, k1 = leafOff + 11 + 16;
        for (int i = 0; i < 8; i++) { byte tmp = bytes[k0 + i]; bytes[k0 + i] = bytes[k1 + i]; bytes[k1 + i] = tmp; }
        Files.write(file, bytes);
        try (BPlusTree t = BPlusTree.open(file, 64, 8)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, t::check);
            assertTrue(e.getMessage().contains("order"), e.getMessage());
        }
    }

    @Test
    void pageSizeTooSmallRejected() {
        assertThrows(IllegalArgumentException.class, () -> open("s.db", 32, 8));
        assertThrows(IllegalArgumentException.class, () -> open("s2.db", 64, 2));
    }
}
