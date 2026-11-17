package ae.heap;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapFileTest {
    @TempDir Path dir;

    private static byte[] b(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    @Test
    void missingFileIsEmptyHeap() throws Exception {
        HeapFile h = HeapFile.open(dir.resolve("h.db"), 128);
        assertTrue(h.scan().isEmpty());
        assertEquals(0, h.pageCount());
    }

    @Test
    void insertGetFlushReopen() throws Exception {
        Path f = dir.resolve("h.db");
        HeapFile h = HeapFile.open(f, 128);
        HeapFile.Rid r1 = h.insert(b("one"));
        HeapFile.Rid r2 = h.insert(b("two"));
        assertEquals(new HeapFile.Rid(0, 0), r1);
        assertEquals(new HeapFile.Rid(0, 1), r2);
        h.flush();
        HeapFile g = HeapFile.open(f, 128);
        assertArrayEquals(b("one"), g.get(r1).orElseThrow());
        assertArrayEquals(b("two"), g.get(r2).orElseThrow());
    }

    @Test
    void spillsToNewPages() throws Exception {
        HeapFile h = HeapFile.open(dir.resolve("h.db"), 64);
        List<HeapFile.Rid> rids = new ArrayList<>();
        for (int i = 0; i < 10; i++) rids.add(h.insert(new byte[30]));  // 34 per record, one per page (56 usable)
        assertEquals(10, h.pageCount());
        assertEquals(new HeapFile.Rid(9, 0), rids.get(9));
        List<HeapFile.Entry> all = h.scan();
        assertEquals(10, all.size());
        for (int i = 0; i < 10; i++) assertEquals(rids.get(i), all.get(i).rid());
    }

    @Test
    void firstFitReusesFreedSpaceInEarlierPage() throws Exception {
        HeapFile h = HeapFile.open(dir.resolve("h.db"), 64);
        HeapFile.Rid a = h.insert(new byte[30]);
        h.insert(new byte[30]);
        assertTrue(h.delete(a));
        assertFalse(h.delete(a));
        assertEquals(a, h.insert(new byte[28]));
        assertEquals(2, h.pageCount());
    }

    @Test
    void oversizedRecordRejectedAndMaxSizeAccepted() throws Exception {
        HeapFile h = HeapFile.open(dir.resolve("h.db"), 64);
        assertThrows(IllegalArgumentException.class, () -> h.insert(new byte[53]));
        assertEquals(0, h.pageCount());
        assertEquals(new HeapFile.Rid(0, 0), h.insert(new byte[52]));
    }

    @Test
    void updateInPlaceOrFalse() throws Exception {
        HeapFile h = HeapFile.open(dir.resolve("h.db"), 64);
        HeapFile.Rid r = h.insert(new byte[20]);
        h.insert(new byte[20]);
        assertTrue(h.update(r, b("short")));
        assertArrayEquals(b("short"), h.get(r).orElseThrow());
        assertFalse(h.update(r, new byte[50]));
        assertArrayEquals(b("short"), h.get(r).orElseThrow());
        assertFalse(h.update(new HeapFile.Rid(7, 0), b("x")));
        assertTrue(h.get(new HeapFile.Rid(7, 0)).isEmpty());
        assertFalse(h.delete(new HeapFile.Rid(7, 0)));
    }

    @Test
    void flushLeavesNoTempFilesAndOverwrites() throws Exception {
        Path f = dir.resolve("h.db");
        HeapFile h = HeapFile.open(f, 128);
        h.insert(b("x"));
        h.flush();
        h.insert(b("y"));
        h.flush();
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(List.of("h.db"), s.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals(2, HeapFile.open(f, 128).scan().size());
        assertEquals(16 + 128, Files.size(f));
    }

    @Test
    void pageSizeMismatchRejected() throws Exception {
        Path f = dir.resolve("h.db");
        HeapFile h = HeapFile.open(f, 128);
        h.insert(b("x"));
        h.flush();
        assertThrows(CorruptDataException.class, () -> HeapFile.open(f, 256));
    }

    @Test
    void truncationAndBitFlipsDetected() throws Exception {
        Path f = dir.resolve("h.db");
        HeapFile h = HeapFile.open(f, 64);
        for (int i = 0; i < 3; i++) h.insert(new byte[30]);
        h.flush();
        byte[] good = Files.readAllBytes(f);
        assertEquals(16 + 3 * 64, good.length);
        for (int cut : new int[] {0, 5, 15, 16, 16 + 64, good.length - 1}) {
            Files.write(f, Arrays.copyOf(good, cut));
            if (cut == 0) { assertTrue(HeapFile.open(f, 64).scan().isEmpty()); continue; }
            assertThrows(CorruptDataException.class, () -> HeapFile.open(f, 64), "cut " + cut);
        }
        for (int i = 0; i < good.length; i++) {
            byte[] bad = good.clone();
            bad[i] ^= 0x10;
            Files.write(f, bad);
            int idx = i;
            assertThrows(CorruptDataException.class, () -> HeapFile.open(f, 64), "byte " + idx);
        }
    }

    @Test
    void randomisedModelCheckWithReopen() throws Exception {
        Path f = dir.resolve("h.db");
        Random rnd = new Random(7);
        HeapFile h = HeapFile.open(f, 128);
        Map<HeapFile.Rid, byte[]> model = new HashMap<>();
        for (int step = 0; step < 600; step++) {
            int op = rnd.nextInt(5);
            if (op <= 1) {
                byte[] d = new byte[rnd.nextInt(60)];
                rnd.nextBytes(d);
                HeapFile.Rid r = h.insert(d);
                assertNull(model.put(r, d));
            } else if (op == 2 && !model.isEmpty()) {
                HeapFile.Rid r = new ArrayList<>(model.keySet()).get(rnd.nextInt(model.size()));
                assertTrue(h.delete(r));
                model.remove(r);
            } else if (op == 3 && !model.isEmpty()) {
                HeapFile.Rid r = new ArrayList<>(model.keySet()).get(rnd.nextInt(model.size()));
                byte[] d = new byte[rnd.nextInt(60)];
                rnd.nextBytes(d);
                if (h.update(r, d)) model.put(r, d);
            } else {
                h.flush();
                h = HeapFile.open(f, 128);
            }
        }
        assertEquals(model.size(), h.scan().size());
        for (HeapFile.Entry e : h.scan()) assertArrayEquals(model.get(e.rid()), e.data());
        List<HeapFile.Rid> order = h.scan().stream().map(HeapFile.Entry::rid).toList();
        assertEquals(order.stream().sorted(Comparator.comparingInt(HeapFile.Rid::page).thenComparingInt(HeapFile.Rid::slot)).toList(), order);
    }
}
