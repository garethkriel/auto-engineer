package ae.sst;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SstTest {
    @TempDir Path dir;

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static Entry e(String k, String v) { return new Entry(k, v == null ? null : b(v)); }

    Path write(String name, List<Entry> es) throws IOException {
        Path p = dir.resolve(name);
        SstWriter.write(p, es);
        return p;
    }

    static List<Entry> numbered(int n) {
        List<Entry> es = new ArrayList<>();
        for (int i = 0; i < n; i++) es.add(e(String.format("key%04d", i), "val" + i));
        return es;
    }

    @Test
    void roundTripEveryKey() throws IOException {
        List<Entry> es = numbered(37); // not a multiple of the index interval (4)
        Path p = write("a.sst", es);
        SstReader r = SstReader.open(p);
        assertEquals(37, r.count());
        for (int i = 0; i < 37; i++) {
            Lookup l = r.get(String.format("key%04d", i));
            assertEquals(Lookup.Kind.FOUND, l.kind());
            assertEquals("val" + i, new String(l.value(), StandardCharsets.UTF_8));
        }
        List<Entry> back = r.entries();
        assertEquals(37, back.size());
        for (int i = 0; i < 37; i++) {
            assertEquals(es.get(i).key(), back.get(i).key());
            assertArrayEquals(es.get(i).value(), back.get(i).value());
        }
    }

    @Test
    void absentKeysBetweenBeforeAndAfter() throws IOException {
        SstReader r = SstReader.open(write("a.sst", numbered(20)));
        assertEquals(Lookup.Kind.ABSENT, r.get("key0005x").kind());
        assertEquals(Lookup.Kind.ABSENT, r.get("a").kind());
        assertEquals(Lookup.Kind.ABSENT, r.get("zzz").kind());
        assertEquals(Lookup.Kind.ABSENT, r.get("key0020").kind());
    }

    @Test
    void tombstoneIsDeletedNotAbsentAndEmptyValueIsFound() throws IOException {
        SstReader r = SstReader.open(write("t.sst", List.of(e("a", ""), e("b", null), e("c", "x"))));
        assertEquals(Lookup.Kind.DELETED, r.get("b").kind());
        assertNull(r.get("b").value());
        Lookup a = r.get("a");
        assertEquals(Lookup.Kind.FOUND, a.kind());
        assertEquals(0, a.value().length);
        assertTrue(r.entries().get(1).isTombstone());
    }

    @Test
    void emptyTable() throws IOException {
        SstReader r = SstReader.open(write("e.sst", List.of()));
        assertEquals(0, r.count());
        assertEquals(Lookup.Kind.ABSENT, r.get("x").kind());
        assertTrue(r.scan(null, null).isEmpty());
    }

    @Test
    void utf8ByteOrderNotUtf16Order() throws IOException {
        String bmp = "￿";            // UTF-8: EF BF BF
        String supp = "😀";     // U+1F600, UTF-8: F0 9F 98 80, sorts AFTER in byte order
        assertTrue(bmp.compareTo(supp) > 0, "String.compareTo puts the BMP char after the surrogate pair");
        assertTrue(Entry.compareKeys(bmp, supp) < 0);
        SstReader r = SstReader.open(write("u.sst", List.of(e(bmp, "1"), e(supp, "2"))));
        assertEquals("2", new String(r.get(supp).value(), StandardCharsets.UTF_8));
        assertEquals("1", new String(r.get(bmp).value(), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> SstWriter.write(dir.resolve("bad.sst"), List.of(e(supp, "2"), e(bmp, "1"))));
    }

    @Test
    void scanBounds() throws IOException {
        SstReader r = SstReader.open(write("s.sst", numbered(30)));
        List<String> keys = r.scan("key0010", "key0015").stream().map(Entry::key).toList();
        assertEquals(List.of("key0010", "key0011", "key0012", "key0013", "key0014"), keys);
        assertEquals(30, r.scan(null, null).size());
        assertEquals(List.of("key0029"), r.scan("key0029", null).stream().map(Entry::key).toList());
        assertEquals(3, r.scan(null, "key0003").size());
        assertTrue(r.scan("key0010", "key0010").isEmpty());
        assertEquals(List.of("key0011"), r.scan("key00105", "key00115").stream().map(Entry::key).toList());
    }

    @Test
    void invalidInputCreatesNoFile() throws IOException {
        Path p = dir.resolve("x.sst");
        assertThrows(IllegalArgumentException.class, () -> SstWriter.write(p, List.of(e("b", "1"), e("a", "2"))));
        assertThrows(IllegalArgumentException.class, () -> SstWriter.write(p, List.of(e("a", "1"), e("a", "2"))));
        assertThrows(IllegalArgumentException.class, () -> SstWriter.write(p, List.of(e("", "1"))));
        assertThrows(IllegalArgumentException.class, () -> new Entry("k".repeat(65536), null));
        try (var s = Files.list(dir)) { assertEquals(0, s.count(), "no file or temp file may remain"); }
    }

    @Test
    void writeIsAtomicOverwriteAndLeavesNoTemp() throws IOException {
        Path p = write("o.sst", numbered(5));
        SstWriter.write(p, List.of(e("only", "1")));
        assertEquals(1, SstReader.open(p).count());
        try (var s = Files.list(dir)) { assertEquals(1, s.count()); }
        // failure on an existing file keeps the old content
        assertThrows(IllegalArgumentException.class, () -> SstWriter.write(p, List.of(e("z", "1"), e("a", "1"))));
        assertEquals(1, SstReader.open(p).count());
        try (var s = Files.list(dir)) { assertEquals(1, s.count()); }
    }

    @Test
    void everySingleByteFlipIsDetected() throws IOException {
        Path p = write("c.sst", List.of(e("alpha", "1"), e("beta", null), e("gamma", "333"), e("zeta", "4"), e("zz", "55")));
        byte[] good = Files.readAllBytes(p);
        Path bad = dir.resolve("bad.sst");
        for (int i = 0; i < good.length; i++) {
            byte[] c = good.clone();
            c[i] ^= 0x01;
            Files.write(bad, c);
            final int pos = i;
            assertThrows(CorruptSstException.class, () -> SstReader.open(bad), "flip at byte " + pos);
        }
        for (int len : new int[] {0, 3, good.length - 1}) {
            Files.write(bad, java.util.Arrays.copyOf(good, len));
            assertThrows(CorruptSstException.class, () -> SstReader.open(bad));
        }
    }

    /** Re-seal a crafted image with a valid CRC so only structural validation can reject it. */
    static byte[] reseal(byte[] img) {
        CRC32 crc = new CRC32();
        crc.update(img, 0, img.length - 8);
        ByteBuffer.wrap(img).putInt(img.length - 8, (int) crc.getValue());
        return img;
    }

    @Test
    void validCrcButBadOffsetsRejected() throws IOException {
        byte[] good = Files.readAllBytes(write("g.sst", numbered(9)));
        int footer = good.length - 28;
        Path bad = dir.resolve("bad.sst");
        long idxOk = ByteBuffer.wrap(good).getLong(footer);
        long bloomOk = ByteBuffer.wrap(good).getLong(footer + 8);
        long[][] cases = {
            {good.length, bloomOk},   // index offset beyond the footer
            {2, bloomOk},             // index offset inside the magic
            {idxOk, 1L << 40},        // bloom offset absurdly large
            {idxOk, idxOk - 1},       // bloom offset before index offset
            {-5, bloomOk},            // negative index offset
        };
        for (long[] cs : cases) {
            byte[] c = good.clone();
            ByteBuffer bb = ByteBuffer.wrap(c);
            bb.putLong(footer, cs[0]);
            bb.putLong(footer + 8, cs[1]);
            Files.write(bad, reseal(c));
            assertThrows(CorruptSstException.class, () -> SstReader.open(bad), java.util.Arrays.toString(cs));
        }
        // count lying about the number of entries, with valid CRC
        byte[] c = good.clone();
        ByteBuffer.wrap(c).putInt(footer + 16, 1000);
        Files.write(bad, reseal(c));
        assertThrows(CorruptSstException.class, () -> SstReader.open(bad));
    }

    @Test
    void bloomNoFalseNegativesAndLowFalsePositives() {
        BloomFilter f = BloomFilter.forCount(1000);
        for (int i = 0; i < 1000; i++) f.add(b("present-" + i));
        for (int i = 0; i < 1000; i++) assertTrue(f.mightContain(b("present-" + i)));
        int fp = 0;
        for (int i = 0; i < 10000; i++) if (f.mightContain(b("absent-" + i))) fp++;
        assertTrue(fp < 300, "false positives " + fp);
        BloomFilter g = BloomFilter.fromBytes(f.toBytes());
        for (int i = 0; i < 1000; i++) assertTrue(g.mightContain(b("present-" + i)));
        assertArrayEquals(f.toBytes(), g.toBytes());
    }

    @Test
    void bloomRejectsMalformedBytes() {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.fromBytes(new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.fromBytes(new byte[] {0, 0, 0, 0, 1, 0, 0, 0, 0}));
    }

    @Test
    void mergeNewestWinsAndTombstoneHandling() throws IOException {
        SstReader older = SstReader.open(write("old.sst", List.of(e("a", "old"), e("b", "old"), e("c", "old"), e("e", "old"))));
        SstReader newer = SstReader.open(write("new.sst", List.of(e("b", null), e("c", "new"), e("d", "new"))));
        Path keep = dir.resolve("keep.sst");
        assertEquals(5, SstMerge.merge(keep, List.of(newer, older), false));
        SstReader k = SstReader.open(keep);
        assertEquals(Lookup.Kind.DELETED, k.get("b").kind());
        assertEquals("new", new String(k.get("c").value(), StandardCharsets.UTF_8));
        assertEquals("old", new String(k.get("a").value(), StandardCharsets.UTF_8));
        Path drop = dir.resolve("drop.sst");
        assertEquals(4, SstMerge.merge(drop, List.of(newer, older), true));
        SstReader d = SstReader.open(drop);
        assertEquals(Lookup.Kind.ABSENT, d.get("b").kind());
        assertEquals(List.of("a", "c", "d", "e"), d.entries().stream().map(Entry::key).toList());
        assertEquals(0, SstMerge.merge(dir.resolve("none.sst"), List.of(), true));
    }

    @Test
    void mergeMatchesTreeMapModelOnRandomData() throws IOException {
        Random rnd = new Random(42);
        for (int round = 0; round < 25; round++) {
            int tables = 1 + rnd.nextInt(4);
            List<SstReader> newestFirst = new ArrayList<>();
            List<TreeMap<String, byte[]>> maps = new ArrayList<>();
            for (int t = 0; t < tables; t++) {
                TreeMap<String, byte[]> m = new TreeMap<>(Entry::compareKeys);
                int n = rnd.nextInt(40);
                for (int i = 0; i < n; i++) {
                    String k = "k" + rnd.nextInt(60);
                    m.put(k, rnd.nextInt(4) == 0 ? null : b("t" + t + "v" + rnd.nextInt(100)));
                }
                List<Entry> es = new ArrayList<>();
                m.forEach((k, v) -> es.add(new Entry(k, v)));
                newestFirst.add(SstReader.open(write("r" + round + "_" + t + ".sst", es)));
                maps.add(m);
            }
            boolean drop = rnd.nextBoolean();
            TreeMap<String, byte[]> model = new TreeMap<>(Entry::compareKeys);
            for (int t = tables - 1; t >= 0; t--) model.putAll(maps.get(t)); // oldest first, newer overwrite
            if (drop) model.values().removeIf(v -> v == null);
            Path out = dir.resolve("m" + round + ".sst");
            assertEquals(model.size(), SstMerge.merge(out, newestFirst, drop));
            List<Entry> got = SstReader.open(out).entries();
            assertEquals(model.size(), got.size());
            int i = 0;
            for (var me : model.entrySet()) {
                assertEquals(me.getKey(), got.get(i).key());
                assertArrayEquals(me.getValue(), got.get(i).value());
                i++;
            }
        }
    }
}
