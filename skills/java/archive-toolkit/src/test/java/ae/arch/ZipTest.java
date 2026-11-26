package ae.arch;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ae.arch.ZipWriter.Method;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipTest {
    private static final List<ArchiveEntry> THREE = List.of(
        TestData.file("ab/cde", "hello hello hello hello"),
        new ArchiveEntry("dir/", new byte[0]),
        TestData.file("dir/x.txt", "x".repeat(300)));

    private static ByteBuffer le(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int eocd(byte[] z) {
        for (int i = z.length - 22; i >= 0; i--) {
            if (le(z).getInt(i) == 0x06054b50) {
                return i;
            }
        }
        throw new AssertionError("no eocd");
    }

    /** Offset of the central header of entry k (entries all have no extra/comment fields). */
    private static int central(byte[] z, int k) {
        int p = le(z).getInt(eocd(z) + 16);
        for (int i = 0; i < k; i++) {
            p += 46 + le(z).getShort(p + 28) + le(z).getShort(p + 30) + le(z).getShort(p + 32);
        }
        return p;
    }

    private static int local(byte[] z, int k) {
        return le(z).getInt(central(z, k) + 42);
    }

    private static byte[] zip(List<ArchiveEntry> e, Method m) {
        return ZipWriter.write(e, m, "");
    }

    private static void assertRejected(byte[] z, String fragment) {
        ArchiveException ex = assertThrows(ArchiveException.class, () -> ZipReader.read(z));
        assertTrue(ex.getMessage().toLowerCase().contains(fragment), "expected '" + fragment + "' in: " + ex.getMessage());
    }

    @Test
    void exactLayoutOfOneStoredFile() {
        byte[] z = ZipWriter.write(List.of(TestData.file("a.txt", "hi")), Method.STORED, "");
        // local 30 + name 5 + data 2 = 37; central 46 + 5 = 51; eocd 22 -> 37 + 51 + 22 = 110
        assertEquals(110, z.length);
        ByteBuffer b = le(z);
        assertEquals(0x04034b50, b.getInt(0));
        assertEquals(10, b.getShort(4));        // version needed for stored
        assertEquals(0x0800, b.getShort(6));    // UTF-8 flag only
        assertEquals(0, b.getShort(8));         // method stored
        assertEquals(0, b.getShort(10));        // dos time 00:00:00
        assertEquals(0x21, b.getShort(12));     // dos date 1980-01-01: (0<<9)|(1<<5)|1
        assertEquals(Crc32.of(TestData.bytes("hi")), b.getInt(14) & 0xFFFFFFFFL);
        assertEquals(2, b.getInt(18));
        assertEquals(2, b.getInt(22));
        assertEquals(5, b.getShort(26));
        assertEquals(0, b.getShort(28));
        assertEquals("a.txt", new String(z, 30, 5, StandardCharsets.US_ASCII));
        assertEquals("hi", new String(z, 35, 2, StandardCharsets.US_ASCII));
        assertEquals(0x02014b50, b.getInt(37));
        assertEquals(0, b.getInt(37 + 42));     // local offset
        assertEquals(0x06054b50, b.getInt(88));
        assertEquals(1, b.getShort(88 + 8));
        assertEquals(1, b.getShort(88 + 10));
        assertEquals(51, b.getInt(88 + 12));
        assertEquals(37, b.getInt(88 + 16));
        assertEquals(0, b.getShort(88 + 20));
    }

    @Test
    void roundTripAllMethods() {
        for (Method m : Method.values()) {
            for (long seed = 0; seed < 25; seed++) {
                List<ArchiveEntry> in = TestData.random(seed, 1 + (int) seed % 9, 2500);
                assertEquals(in, ZipReader.read(zip(in, m)), m + " seed " + seed);
            }
        }
        assertEquals(List.of(), ZipReader.read(zip(List.of(), Method.AUTO)));
    }

    @Test
    void methodsAreWhatTheyClaim() {
        byte[] data = "z".repeat(500).getBytes(StandardCharsets.UTF_8);
        byte[] noise = new byte[200];
        new java.util.Random(5).nextBytes(noise);
        List<ArchiveEntry> e = List.of(new ArchiveEntry("zeros", data), new ArchiveEntry("noise", noise), new ArchiveEntry("d/", new byte[0]));
        byte[] stored = zip(e, Method.STORED);
        byte[] deflated = zip(e, Method.DEFLATED);
        byte[] auto = zip(e, Method.AUTO);
        assertEquals(0, le(stored).getShort(central(stored, 0) + 10));
        assertEquals(8, le(deflated).getShort(central(deflated, 0) + 10));
        assertEquals(8, le(deflated).getShort(central(deflated, 1) + 10));
        assertEquals(0, le(deflated).getShort(central(deflated, 2) + 10), "directories are always stored");
        assertEquals(8, le(auto).getShort(central(auto, 0) + 10), "compressible -> deflate");
        assertEquals(0, le(auto).getShort(central(auto, 1) + 10), "incompressible -> stored");
        assertEquals(20, le(deflated).getShort(central(deflated, 0) + 6));
        assertTrue(deflated.length < stored.length + 200);
        assertTrue(auto.length < stored.length);
    }

    @Test
    void autoStoresWhenDeflateIsNotStrictlySmaller() {
        // find, with the JDK deflater, a payload whose deflated size equals its raw size (the tie case)
        byte[] tie = null;
        for (int n = 1; n < 300 && tie == null; n++) {
            byte[] cand = new byte[n];
            new java.util.Random(n).nextBytes(cand);
            for (int i = 0; i < n; i += 2) {
                cand[i] = 'a'; // half predictable: sizes hover around n
            }
            java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
            d.setInput(cand);
            d.finish();
            byte[] buf = new byte[n + 64];
            int got = d.deflate(buf);
            d.end();
            if (got == n) {
                tie = cand;
            }
        }
        assertNotNull(tie, "fixture search must find a deflated-size == raw-size payload");
        byte[] z = zip(List.of(new ArchiveEntry("tie", tie)), Method.AUTO);
        assertEquals(0, le(z).getShort(central(z, 0) + 10), "tie -> stored");
        assertEquals(tie.length, le(z).getInt(central(z, 0) + 20));
    }

    @Test
    void emptyAndTinyFilesRoundTripDeflated() {
        List<ArchiveEntry> e = List.of(new ArchiveEntry("empty", new byte[0]), new ArchiveEntry("one", new byte[] {7}));
        byte[] z = zip(e, Method.DEFLATED);
        assertEquals(8, le(z).getShort(central(z, 0) + 10));
        assertEquals(e, ZipReader.read(z));
    }

    @Test
    void externalAttributesMarkDirectories() {
        byte[] z = zip(THREE, Method.STORED);
        assertEquals(0, le(z).getInt(central(z, 0) + 38));
        assertEquals(0x10, le(z).getInt(central(z, 1) + 38), "MS-DOS directory bit");
        assertEquals(0, le(z).getInt(central(z, 2) + 38));
    }

    @Test
    void commentRoundTripAndLimits() {
        byte[] z = ZipWriter.write(THREE, Method.AUTO, "héllo");
        assertEquals("héllo", ZipReader.comment(z));
        assertEquals(THREE, ZipReader.read(z));
        String max = "c".repeat(65535);
        assertEquals(max, ZipReader.comment(ZipWriter.write(List.of(), Method.STORED, max)));
        assertThrows(IllegalArgumentException.class, () -> ZipWriter.write(List.of(), Method.STORED, "c".repeat(65536)));
        assertThrows(NullPointerException.class, () -> ZipWriter.write(THREE, null, ""));
    }

    @Test
    void fakeEocdInsideCommentIsSkipped() {
        // the comment holds a full-looking EOCD signature; only the real one ends exactly at EOF
        String comment = "PK\u0005\u0006" + "\u0000".repeat(30);
        byte[] z = ZipWriter.write(THREE, Method.STORED, comment);
        assertEquals(THREE, ZipReader.read(z));
        assertEquals(comment, ZipReader.comment(z));
    }

    @Test
    void writerRejectsBadTrees() {
        ArchiveEntry a = TestData.file("a", "1");
        assertThrows(ArchiveException.class, () -> zip(List.of(a, a), Method.STORED));
        assertThrows(ArchiveException.class, () -> zip(List.of(a, TestData.file("a/b", "1")), Method.STORED));
        List<ArchiveEntry> many = new ArrayList<>();
        for (int i = 0; i < 65535; i++) {
            many.add(new ArchiveEntry("f" + i, new byte[0]));
        }
        // 0xFFFF in the entry count is the ZIP64 marker, so 65534 is the most a classic archive can hold
        assertThrows(IllegalArgumentException.class, () -> zip(many, Method.STORED));
        assertEquals(65534, ZipReader.read(zip(many.subList(0, 65534), Method.STORED)).size()); // control
    }

    // ---- differential against the JDK and Info-ZIP ----

    @Test
    void jdkReadsOurArchives(@TempDir Path dir) throws IOException {
        for (Method m : Method.values()) {
            List<ArchiveEntry> in = TestData.random(100 + m.ordinal(), 12, 3000);
            byte[] z = zip(in, m);
            List<ArchiveEntry> viaStream = new ArrayList<>();
            try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(z))) {
                for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                    viaStream.add(new ArchiveEntry(e.getName(), zin.readAllBytes()));
                }
            }
            assertEquals(in, viaStream, "ZipInputStream " + m);
            Path f = dir.resolve(m + ".zip");
            Files.write(f, z);
            List<ArchiveEntry> viaFile = new ArrayList<>();
            try (ZipFile zf = new ZipFile(f.toFile())) {
                for (var en = zf.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    try (var is = zf.getInputStream(e)) {
                        viaFile.add(new ArchiveEntry(e.getName(), is.readAllBytes()));
                    }
                    assertEquals(e.getName().endsWith("/"), e.isDirectory());
                }
            }
            assertEquals(in, viaFile, "ZipFile " + m);
        }
    }

    @Test
    void weReadJdkArchivesIncludingDataDescriptors() throws IOException {
        List<ArchiveEntry> in = TestData.random(321, 14, 3000);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream zo = new ZipOutputStream(bo)) {
            int i = 0;
            for (ArchiveEntry e : in) {
                ZipEntry ze = new ZipEntry(e.name());
                if (i++ % 3 == 0 && !e.directory()) { // STORED needs size and crc up front
                    ze.setMethod(ZipEntry.STORED);
                    ze.setSize(e.data().length);
                    ze.setCompressedSize(e.data().length);
                    ze.setCrc(Crc32.of(e.data()));
                }
                zo.putNextEntry(ze);
                zo.write(e.data());
                zo.closeEntry();
            }
        }
        byte[] z = bo.toByteArray();
        boolean sawDescriptor = false;
        for (int k = 0; k < in.size(); k++) {
            sawDescriptor |= (le(z).getShort(local(z, k) + 6) & 8) != 0;
        }
        assertTrue(sawDescriptor, "fixture must contain a bit-3 entry");
        assertEquals(in, ZipReader.read(z));
    }

    private static boolean tool(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String run(Path cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), String.join(" ", cmd) + "\n" + out);
        return out;
    }

    @Test
    void unzipAcceptsOurArchives(@TempDir Path dir) throws Exception {
        assumeTrue(tool("unzip", "-v"), "unzip not installed");
        for (Method m : Method.values()) {
            List<ArchiveEntry> in = TestData.random(40 + m.ordinal(), 10, 2000, false);
            Path f = dir.resolve(m + ".zip");
            Files.write(f, zip(in, m));
            run(dir, "unzip", "-tq", f.toString());
            Path out = dir.resolve("out-" + m);
            run(dir, "unzip", "-q", f.toString(), "-d", out.toString());
            assertEquals(Archives.snapshot(out), sortedDirsFirstView(in), "extracted by unzip " + m);
        }
    }

    /** Snapshot of the extracted tree includes implicit parents; the input only lists some, so compare after normalising. */
    private static List<ArchiveEntry> sortedDirsFirstView(List<ArchiveEntry> in) {
        java.util.TreeMap<String, ArchiveEntry> map = new java.util.TreeMap<>();
        for (ArchiveEntry e : in) {
            String[] parts = e.name().split("/");
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                p.append(parts[i]).append('/');
                map.putIfAbsent(p.toString(), new ArchiveEntry(p.toString(), new byte[0]));
            }
            map.put(e.name(), e);
        }
        return new ArrayList<>(map.values());
    }

    @Test
    void weReadInfoZipArchives(@TempDir Path dir) throws Exception {
        assumeTrue(tool("zip", "-v"), "zip not installed");
        Path src = dir.resolve("src");
        List<ArchiveEntry> in = TestData.random(77, 10, 4000, false);
        Archives.extractTo(src, in);
        Path z = dir.resolve("t.zip");
        run(src, "zip", "-q", "-r", z.toString(), ".");
        List<ArchiveEntry> got = ZipReader.read(Files.readAllBytes(z));
        // zip(1) writes "./"-less names relative to cwd and adds directory entries for every directory
        java.util.TreeMap<String, ArchiveEntry> byName = new java.util.TreeMap<>();
        for (ArchiveEntry e : got) {
            byName.put(e.name(), e);
        }
        for (ArchiveEntry e : sortedDirsFirstView(in)) {
            assertEquals(e, byName.get(e.name()), e.name());
        }
        assertEquals(sortedDirsFirstView(in).size(), got.size());
        // and with -0 (stored) and extra fields (zip adds UT/ux extra fields by default)
        Path z0 = dir.resolve("t0.zip");
        run(src, "zip", "-q", "-0", "-r", z0.toString(), ".");
        assertEquals(got.size(), ZipReader.read(Files.readAllBytes(z0)).size());
    }

    // ---- strictness: each crafted defect has a control that differs only by that defect ----

    @Test
    void controlLoads() {
        assertEquals(THREE, ZipReader.read(zip(THREE, Method.DEFLATED)));
        assertEquals(THREE, ZipReader.read(zip(THREE, Method.STORED)));
    }

    @Test
    void crcMismatchRejected() {
        byte[] z = zip(THREE, Method.STORED);
        le(z).putInt(central(z, 0) + 16, le(z).getInt(central(z, 0) + 16) ^ 1);
        le(z).putInt(local(z, 0) + 14, le(z).getInt(local(z, 0) + 14) ^ 1); // local agrees: only the data CRC is wrong
        assertRejected(z, "crc");
    }

    @Test
    void localCentralDisagreementRejected() {
        byte[] z = zip(THREE, Method.STORED);
        le(z).putInt(local(z, 0) + 14, le(z).getInt(local(z, 0) + 14) ^ 1); // only local differs
        assertRejected(z, "local");
        byte[] z2 = zip(THREE, Method.STORED);
        le(z2).putShort(local(z2, 0) + 8, (short) 8); // method differs
        assertRejected(z2, "method");
        byte[] z3 = zip(THREE, Method.STORED);
        z3[local(z3, 0) + 30] = 'X'; // name differs
        assertRejected(z3, "name");
    }

    @Test
    void sizeMismatchRejected() {
        List<ArchiveEntry> one = List.of(TestData.file("f", "q".repeat(400)));
        byte[] z = zip(one, Method.DEFLATED);
        int c = central(z, 0);
        int declared = le(z).getInt(c + 24);
        assertEquals(400, declared);
        // stream is longer than the declared size
        byte[] shorter = z.clone();
        le(shorter).putInt(c + 24, 399);
        le(shorter).putInt(local(shorter, 0) + 22, 399);
        assertRejected(shorter, "size");
        // stream is shorter than the declared size
        byte[] longer = z.clone();
        le(longer).putInt(c + 24, 401);
        le(longer).putInt(local(longer, 0) + 22, 401);
        assertRejected(longer, "size");
        // one slack byte after the deflate stream: harmless unless the headers claim it as compressed data
        int ds = local(z, 0) + 30 + 1;
        int de = ds + le(z).getInt(c + 20);
        byte[] slack = new byte[z.length + 1];
        System.arraycopy(z, 0, slack, 0, de);
        System.arraycopy(z, de, slack, de + 1, z.length - de);
        le(slack).putInt(eocd(slack) + 16, le(z).getInt(eocd(z) + 16) + 1);
        assertEquals(one, ZipReader.read(slack), "control: unclaimed slack byte");
        le(slack).putInt(central(slack, 0) + 20, le(z).getInt(c + 20) + 1);
        le(slack).putInt(local(slack, 0) + 18, le(z).getInt(c + 20) + 1);
        assertRejected(slack, "compressed");
        assertEquals(one, ZipReader.read(z)); // control
    }

    @Test
    void sizeCapIsCheckedBeforeAllocation() {
        List<ArchiveEntry> one = List.of(new ArchiveEntry("big", new byte[5000]));
        byte[] z = zip(one, Method.DEFLATED);
        assertEquals(one, ZipReader.read(z, 5000));
        assertRejected2(z, 4999);
        // a lying header claims 4 GiB-2; reading must fail fast without allocating it
        byte[] lie = z.clone();
        le(lie).putInt(central(lie, 0) + 24, 0xFFFFFFFE);
        le(lie).putInt(local(lie, 0) + 22, 0xFFFFFFFE);
        ArchiveException ex = assertThrows(ArchiveException.class, () -> ZipReader.read(lie));
        assertTrue(ex.getMessage().contains("too large"), ex.getMessage());
    }

    private static void assertRejected2(byte[] z, long cap) {
        ArchiveException ex = assertThrows(ArchiveException.class, () -> ZipReader.read(z, cap));
        assertTrue(ex.getMessage().contains("too large"), ex.getMessage());
    }

    @Test
    void zip64AndFlagsAndMethodsRejected() {
        byte[] z = zip(THREE, Method.STORED);
        byte[] z64 = z.clone();
        le(z64).putShort(eocd(z64) + 8, (short) 0xFFFF);
        le(z64).putShort(eocd(z64) + 10, (short) 0xFFFF);
        assertRejected(z64, "zip64");
        byte[] sz = z.clone();
        le(sz).putInt(central(sz, 0) + 24, 0xFFFFFFFF);
        assertRejected(sz, "zip64");
        byte[] enc = z.clone();
        le(enc).putShort(central(enc, 0) + 8, (short) 0x0801);
        le(enc).putShort(local(enc, 0) + 6, (short) 0x0801);
        assertRejected(enc, "encrypted");
        byte[] flag = z.clone();
        le(flag).putShort(central(flag, 0) + 8, (short) 0x0810 );
        assertRejected(flag, "flag");
        byte[] meth = z.clone();
        le(meth).putShort(central(meth, 0) + 10, (short) 9);
        le(meth).putShort(local(meth, 0) + 8, (short) 9);
        assertRejected(meth, "method");
        byte[] ver = z.clone();
        le(ver).putShort(central(ver, 0) + 6, (short) 45);
        assertRejected(ver, "version");
        byte[] disk = z.clone();
        le(disk).putShort(central(disk, 0) + 34, (short) 1);
        assertRejected(disk, "disk");
        byte[] eodisk = z.clone();
        le(eodisk).putShort(eocd(eodisk) + 4, (short) 1);
        assertRejected(eodisk, "disk");
        byte[] cddisk = z.clone();
        le(cddisk).putShort(eocd(cddisk) + 6, (short) 1); // disk holding the central directory
        assertRejected(cddisk, "disk");
        byte[] storedMismatch = z.clone();
        le(storedMismatch).putInt(central(storedMismatch, 0) + 20, le(z).getInt(central(z, 0) + 20) + 1);
        assertRejected(storedMismatch, "stored");
        assertEquals(THREE, ZipReader.read(z));
    }

    @Test
    void eocdAndDirectoryStructureRejected() {
        byte[] z = zip(THREE, Method.STORED);
        int e = eocd(z);
        byte[] count = z.clone();
        le(count).putShort(e + 8, (short) 2);
        le(count).putShort(e + 10, (short) 2);
        assertRejected(count, "central");
        byte[] mismatch = z.clone();
        le(mismatch).putShort(e + 8, (short) 2);
        assertRejected(mismatch, "entries");
        byte[] moreCount = z.clone();
        le(moreCount).putShort(e + 8, (short) 4);
        le(moreCount).putShort(e + 10, (short) 4);
        assertRejected(moreCount, "central");
        byte[] gap = z.clone();
        le(gap).putInt(e + 12, le(z).getInt(e + 12) + 1); // central size one too large
        assertRejected(gap, "central");
        byte[] off = z.clone();
        le(off).putInt(e + 16, le(z).getInt(e + 16) - 1);
        assertRejected(off, "central");
        byte[] cl = z.clone();
        le(cl).putShort(e + 20, (short) 1); // comment length claims a byte that is not there
        assertRejected(cl, "end of central");
        byte[] trailing = java.util.Arrays.copyOf(z, z.length + 1);
        assertRejected(trailing, "end of central");
        byte[] withGap = new byte[z.length + 1]; // one stray byte between the directory and the EOCD
        System.arraycopy(z, 0, withGap, 0, e);
        System.arraycopy(z, e, withGap, e + 1, z.length - e);
        assertRejected(withGap, "central");
        byte[] overrun = z.clone();
        le(overrun).putShort(central(overrun, 2) + 32, (short) 1); // last header's comment runs 1 byte past the directory
        assertRejected(overrun, "central");
        byte[] sig = z.clone();
        sig[central(sig, 1)] ^= 1;
        assertRejected(sig, "central");
        assertRejected(new byte[0], "end of central");
        assertRejected(new byte[21], "end of central");
    }

    @Test
    void nameDefectsRejected() {
        byte[] z = zip(List.of(TestData.file("ab/cde", "1"), TestData.file("qq/rst", "2")), Method.STORED);
        assertEquals(2, ZipReader.read(z).size());
        assertRejected(patchName(z, 0, "../cde"), "name");
        assertRejected(patchName(z, 0, "/b/cde"), "name");
        assertRejected(patchName(z, 0, "ab\\cde"), "name");
        assertRejected(patchName(z, 0, "ab/./e"), "name");
        assertEquals("xy/cde", ZipReader.read(patchName(z, 0, "xy/cde")).get(0).name()); // control: harmless
        assertRejected(patchName(z, 1, "ab/cde"), "duplicate");
        byte[] badUtf8 = z.clone();
        badUtf8[central(badUtf8, 0) + 46] = (byte) 0xFF;
        badUtf8[local(badUtf8, 0) + 30] = (byte) 0xFF;
        assertRejected(badUtf8, "utf-8");
    }

    @Test
    void slashVariantDuplicateRejected() {
        byte[] z = zip(List.of(new ArchiveEntry("ab/", new byte[0]), TestData.file("abc", "")), Method.STORED);
        assertEquals(2, ZipReader.read(z).size());
        assertRejected(patchName(z, 1, "ab/"), "duplicate"); // "ab/" twice
    }

    /** Overwrites the name of entry k (same length) in both headers. */
    private static byte[] patchName(byte[] z, int k, String name) {
        byte[] c = z.clone();
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        int cp = central(z, k);
        int lp = local(z, k);
        assertEquals(le(z).getShort(cp + 28), n.length);
        System.arraycopy(n, 0, c, cp + 46, n.length);
        System.arraycopy(n, 0, c, lp + 30, n.length);
        return c;
    }

    @Test
    void parentFileConflictRejectedOnRead() {
        byte[] z = zip(List.of(TestData.file("abc", "1"), TestData.file("q/r/s", "2")), Method.STORED);
        assertEquals(2, ZipReader.read(z).size());
        assertRejected(patchName(z, 1, "abc/d"), "parent"); // "abc" is a file and now also a parent
    }

    @Test
    void directoryWithDataRejected() {
        List<ArchiveEntry> e = List.of(TestData.file("dd", "1234"));
        byte[] z = zip(e, Method.STORED);
        assertEquals(e, ZipReader.read(z));
        byte[] d = patchName(z, 0, "d/");
        // the same length is not possible for "dd" -> "d/" so patch: name "d/" has the same 2 bytes
        assertRejected(d, "directory");
    }

    @Test
    void overlappingEntriesRejected() {
        List<ArchiveEntry> e = List.of(TestData.file("aa", "1234"), TestData.file("bb", "5678"));
        byte[] z = zip(e, Method.STORED);
        assertEquals(e, ZipReader.read(z));
        byte[] o = z.clone();
        int csz = le(z).getInt(central(z, 0) + 20);
        // entry aa now claims 4 + 34 more bytes, running into bb's local header
        for (int[] f : new int[][] {{central(z, 0) + 20}, {central(z, 0) + 24}, {local(z, 0) + 18}, {local(z, 0) + 22}}) {
            le(o).putInt(f[0], csz + 34);
        }
        assertRejected(o, "overlap");
    }

    @Test
    void overlapAndOverrunByExactlyOneByteRejected() {
        List<ArchiveEntry> e = List.of(TestData.file("aa", "1234"), TestData.file("bb", "5678"));
        byte[] z = zip(e, Method.STORED);
        for (int k = 0; k < 2; k++) { // k=0: into the next entry; k=1: into the central directory
            byte[] o = z.clone();
            int csz = le(z).getInt(central(z, k) + 20);
            for (int f : new int[] {central(z, k) + 20, central(z, k) + 24, local(z, k) + 18, local(z, k) + 22}) {
                le(o).putInt(f, csz + 1);
            }
            assertRejected(o, k == 0 ? "overlap" : "central directory");
        }
    }

    @Test
    void deflateStreamCutShortIsRejected() {
        List<ArchiveEntry> one = List.of(TestData.file("f", "q".repeat(400)));
        byte[] z = zip(one, Method.DEFLATED);
        int csz = le(z).getInt(central(z, 0) + 20);
        for (int cut = 1; cut <= csz; cut++) {
            byte[] c = z.clone();
            le(c).putInt(central(c, 0) + 20, csz - cut);
            le(c).putInt(local(c, 0) + 18, csz - cut);
            // the headers now describe a shorter stream; whatever the inflater makes of it, it must not be accepted
            assertThrows(ArchiveException.class, () -> ZipReader.read(c), "cut " + cut);
        }
        assertEquals(one, ZipReader.read(z));
    }

    @Test
    void everyTruncationRejected() {
        for (Method m : new Method[] {Method.STORED, Method.DEFLATED}) {
            byte[] z = zip(THREE, m);
            for (int n = 0; n < z.length; n++) {
                int len = n;
                byte[] cut = java.util.Arrays.copyOf(z, len);
                assertThrows(ArchiveException.class, () -> ZipReader.read(cut), m + " truncated to " + len);
            }
        }
    }

    @Test
    void everyByteFlipRejectedOrHarmless() {
        for (Method m : new Method[] {Method.STORED, Method.DEFLATED}) {
            byte[] z = ZipWriter.write(THREE, m, "cm");
            int rejected = 0;
            for (int i = 0; i < z.length; i++) {
                for (int bit : new int[] {0, 7}) {
                    byte[] f = z.clone();
                    f[i] ^= (byte) (1 << bit);
                    try {
                        List<ArchiveEntry> got = ZipReader.read(f);
                        assertEquals(THREE, got, m + " flip byte " + i + " bit " + bit + " accepted but changed data");
                    } catch (ArchiveException expected) {
                        rejected++;
                    }
                }
            }
            assertTrue(rejected > z.length, m + ": most flips must be caught, got " + rejected + " of " + 2 * z.length);
        }
    }
}
