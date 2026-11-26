package ae.arch;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TarTest {
    private static final List<ArchiveEntry> THREE = List.of(
        TestData.file("hello.txt", "hello tar"),
        new ArchiveEntry("dir/", new byte[0]),
        TestData.file("dir/big.bin", "0123456789".repeat(100))); // 1000 bytes -> 2 data blocks

    private static void assertRejected(byte[] t, String fragment) {
        ArchiveException ex = assertThrows(ArchiveException.class, () -> TarReader.read(t));
        assertTrue(ex.getMessage().toLowerCase().contains(fragment), "expected '" + fragment + "' in: " + ex.getMessage());
    }

    /** Recomputes the checksum of the header at {@code off} so a crafted field change stays "valid" otherwise. */
    private static void reseal(byte[] t, int off) {
        Arrays.fill(t, off + 148, off + 156, (byte) ' ');
        int sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += t[off + i] & 0xFF;
        }
        byte[] f = String.format("%06o", sum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(f, 0, t, off + 148, 6);
        t[off + 154] = 0;
        t[off + 155] = ' ';
    }

    @Test
    void exactLayoutOfOneFile() {
        byte[] t = TarWriter.write(List.of(TestData.file("a.txt", "hi")));
        assertEquals(512 + 512 + 1024, t.length); // header + one data block + two zero blocks
        assertEquals("a.txt", new String(t, 0, 5, StandardCharsets.US_ASCII));
        assertEquals(0, t[5]);
        assertEquals("0000644\0", new String(t, 100, 8, StandardCharsets.US_ASCII));
        assertEquals("0000000\0", new String(t, 108, 8, StandardCharsets.US_ASCII));
        assertEquals("00000000002\0", new String(t, 124, 12, StandardCharsets.US_ASCII)); // size 2
        assertEquals("00000000000\0", new String(t, 136, 12, StandardCharsets.US_ASCII)); // mtime 0
        assertEquals('0', t[156]);
        assertEquals("ustar\0", new String(t, 257, 6, StandardCharsets.ISO_8859_1));
        assertEquals("00", new String(t, 263, 2, StandardCharsets.US_ASCII));
        int sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (t[i] & 0xFF);
        }
        assertEquals(String.format("%06o", sum), new String(t, 148, 6, StandardCharsets.US_ASCII));
        assertEquals(0, t[154]);
        assertEquals(' ', t[155]);
        assertEquals("hi", new String(t, 512, 2, StandardCharsets.US_ASCII));
        for (int i = 514; i < t.length; i++) {
            assertEquals(0, t[i], "padding/end byte " + i);
        }
        // literal sum, byte values added by hand: name 97+46+116+120+116=495, mode/uid/gid/devmajor/devminor
        // (sum of all 512 header bytes, checksum field counted as 8 spaces) = 4206 = octal 10156
        // name 495 + mode 350 + uid 336 + gid 336 + size("00000000002") 530 + mtime 528 + chksum 8 spaces 256
        // + typeflag '0' 48 + "ustar" 559 + "00" 96 + devmajor 336 + devminor 336 = 4206
        assertEquals(4206, sum);
        assertEquals("010156", new String(t, 148, 6, StandardCharsets.US_ASCII));
    }

    @Test
    void directoryHeader() {
        byte[] t = TarWriter.write(List.of(new ArchiveEntry("d/", new byte[0])));
        assertEquals(512 + 1024, t.length);
        assertEquals('5', t[156]);
        assertEquals("0000755\0", new String(t, 100, 8, StandardCharsets.US_ASCII));
        assertEquals("00000000000\0", new String(t, 124, 12, StandardCharsets.US_ASCII));
        assertEquals("d/", new String(t, 0, 2, StandardCharsets.US_ASCII));
    }

    @Test
    void roundTrip() {
        for (long seed = 0; seed < 25; seed++) {
            List<ArchiveEntry> in = TestData.random(seed, 1 + (int) seed % 9, 2500);
            byte[] t = TarWriter.write(in);
            assertEquals(0, t.length % 512);
            assertEquals(in, TarReader.read(t), "seed " + seed);
        }
        assertEquals(List.of(), TarReader.read(TarWriter.write(List.of())));
        assertEquals(1024, TarWriter.write(List.of()).length);
    }

    @Test
    void exactBlockMultipleDataHasNoExtraBlock() {
        byte[] t = TarWriter.write(List.of(new ArchiveEntry("f", new byte[512])));
        assertEquals(512 + 512 + 1024, t.length);
        byte[] t2 = TarWriter.write(List.of(new ArchiveEntry("f", new byte[513])));
        assertEquals(512 + 1024 + 1024, t2.length);
        assertEquals(513, TarReader.read(t2).get(0).data().length);
    }

    @Test
    void longNamesUsePrefix() {
        String seg = "s".repeat(49); // each segment + "/" = 50 bytes
        for (int segments = 1; segments <= 4; segments++) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < segments; i++) {
                b.append(seg).append('/');
            }
            String name = b + "f"; // length 50*segments + 1
            List<ArchiveEntry> in = List.of(TestData.file(name, "x"));
            byte[] t = TarWriter.write(in);
            boolean usesPrefix = t[345] != 0;
            assertEquals(name.length() > 100, usesPrefix, "length " + name.length());
            assertEquals(in, TarReader.read(t), "length " + name.length());
        }
        // 5 x 50 + 1 = 251 bytes: the only slashes leave prefix > 155 or rest > 100
        String five = (seg + "/").repeat(5) + "f";
        assertThrows(IllegalArgumentException.class, () -> TarWriter.write(List.of(TestData.file(five, ""))));
        // exactly 100 bytes: no prefix; exactly 101: needs the split
        String hundred = "a".repeat(100);
        assertEquals(0, TarWriter.write(List.of(TestData.file(hundred, ""))) [345]);
        String d = "d".repeat(100) + "/" + "e"; // 102 bytes, prefix 100, name 1
        byte[] t = TarWriter.write(List.of(TestData.file(d, "")));
        assertEquals("d".repeat(100), new String(t, 345, 100, StandardCharsets.US_ASCII));
        assertEquals("e", new String(t, 0, 1, StandardCharsets.US_ASCII));
        // longest legal: prefix 155 + '/' + name 100 = 256 bytes
        String longest = "p".repeat(155) + "/" + "n".repeat(100);
        assertEquals(256, longest.length());
        assertEquals(List.of(TestData.file(longest, "z")), TarReader.read(TarWriter.write(List.of(TestData.file(longest, "z")))));
        // one byte more in either part cannot be represented
        assertThrows(IllegalArgumentException.class, () -> TarWriter.write(List.of(TestData.file("p".repeat(156) + "/" + "n".repeat(100), ""))));
        assertThrows(IllegalArgumentException.class, () -> TarWriter.write(List.of(TestData.file("p".repeat(155) + "/" + "n".repeat(101), ""))));
        assertThrows(IllegalArgumentException.class, () -> TarWriter.write(List.of(TestData.file("n".repeat(101), ""))));
        // a trailing slash of a directory never becomes the split point
        String dirName = "q".repeat(101) + "/";
        assertThrows(IllegalArgumentException.class, () -> TarWriter.write(List.of(new ArchiveEntry(dirName, new byte[0]))));
        String dir2 = "q".repeat(60) + "/" + "r".repeat(60) + "/";
        assertEquals(List.of(new ArchiveEntry(dir2, new byte[0])), TarReader.read(TarWriter.write(List.of(new ArchiveEntry(dir2, new byte[0])))));
    }

    @Test
    void byteLengthNotCharLengthCounts() {
        String name = "é".repeat(60); // 120 UTF-8 bytes, no slash -> cannot be split
        assertThrows(IllegalArgumentException.class, () -> TarWriter.write(List.of(TestData.file(name, ""))));
        String ok = "é".repeat(20) + "/" + "é".repeat(40); // 40 + 1 + 80 bytes
        assertEquals(List.of(TestData.file(ok, "k")), TarReader.read(TarWriter.write(List.of(TestData.file(ok, "k")))));
    }

    @Test
    void writerRejectsBadTrees() {
        ArchiveEntry a = TestData.file("a", "1");
        assertThrows(ArchiveException.class, () -> TarWriter.write(List.of(a, a)));
        assertThrows(ArchiveException.class, () -> TarWriter.write(List.of(a, TestData.file("a/b", "1"))));
    }

    // ---- differential against GNU tar ----

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
    void gnuTarReadsOurArchives(@TempDir Path dir) throws Exception {
        assumeTrue(tool("tar", "--version"), "tar not installed");
        for (long seed = 0; seed < 5; seed++) {
            List<ArchiveEntry> in = TestData.random(900 + seed, 12, 3000, false);
            // add a prefix-split name and an empty directory
            String longName = ("L".repeat(60) + "/").repeat(3) + "deep.txt";
            in = new java.util.ArrayList<>(in);
            in.add(TestData.file(longName, "deep " + seed));
            in.add(new ArchiveEntry("emptydir/", new byte[0]));
            Path f = dir.resolve("t" + seed + ".tar");
            Files.write(f, TarWriter.write(in));
            String listing = run(dir, "tar", "-tf", f.toString());
            for (ArchiveEntry e : in) {
                assertTrue(Arrays.asList(listing.split("\n")).contains(e.name()), e.name() + " missing in:\n" + listing);
            }
            Path out = dir.resolve("out" + seed);
            Files.createDirectories(out);
            run(dir, "tar", "-xf", f.toString(), "-C", out.toString());
            assertEquals(viewWithParents(in), Archives.snapshot(out), "seed " + seed);
        }
    }

    static List<ArchiveEntry> viewWithParents(List<ArchiveEntry> in) {
        TreeMap<String, ArchiveEntry> map = new TreeMap<>();
        for (ArchiveEntry e : in) {
            String[] parts = e.name().split("/");
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                p.append(parts[i]).append('/');
                map.putIfAbsent(p.toString(), new ArchiveEntry(p.toString(), new byte[0]));
            }
            map.put(e.name(), e);
        }
        return new java.util.ArrayList<>(map.values());
    }

    @Test
    void weReadGnuTarUstarArchives(@TempDir Path dir) throws Exception {
        assumeTrue(tool("tar", "--version"), "tar not installed");
        for (long seed = 0; seed < 5; seed++) {
            Path src = dir.resolve("src" + seed);
            List<ArchiveEntry> in = new java.util.ArrayList<>(TestData.random(500 + seed, 10, 3000, false));
            in.add(TestData.file(("M".repeat(70) + "/").repeat(2) + "n" + seed, "long " + seed));
            Archives.extractTo(src, in);
            Path t = dir.resolve("g" + seed + ".tar");
            run(src, "tar", "--format=ustar", "--numeric-owner", "-cf", t.toString(), ".");
            List<ArchiveEntry> got = TarReader.read(Files.readAllBytes(t), true);
            assertEquals(viewWithParents(in), sortedWithoutDot(got), "seed " + seed);
        }
    }

    private static List<ArchiveEntry> sortedWithoutDot(List<ArchiveEntry> got) {
        TreeMap<String, ArchiveEntry> map = new TreeMap<>();
        for (ArchiveEntry e : got) {
            map.put(e.name(), e);
        }
        assertEquals(got.size(), map.size());
        return new java.util.ArrayList<>(map.values());
    }

    // ---- strictness ----

    @Test
    void controlLoads() {
        assertEquals(THREE, TarReader.read(TarWriter.write(THREE)));
    }

    @Test
    void headerDefectsRejected() {
        byte[] t = TarWriter.write(THREE);
        byte[] badSum = t.clone();
        badSum[0] ^= 1;
        assertRejected(badSum, "checksum");
        byte[] magic = t.clone();
        magic[257] = 'x';
        reseal(magic, 0);
        assertRejected(magic, "magic");
        byte[] ver = t.clone();
        ver[263] = '1';
        reseal(ver, 0);
        assertRejected(ver, "magic");
        byte[] link = t.clone();
        link[156] = '2'; // symlink
        reseal(link, 0);
        assertRejected(link, "typeflag");
        byte[] pax = t.clone();
        pax[156] = 'x';
        reseal(pax, 0);
        assertRejected(pax, "typeflag");
        byte[] nul = t.clone();
        nul[156] = 0; // old-style regular file marker is accepted
        reseal(nul, 0);
        assertEquals(THREE, TarReader.read(nul));
        byte[] octal = t.clone();
        octal[124] = '9'; // size not octal
        reseal(octal, 0);
        assertRejected(octal, "octal");
        byte[] mode = t.clone();
        mode[100] = 'z';
        reseal(mode, 0);
        assertRejected(mode, "octal");
        byte[] noTerminator = t.clone();
        System.arraycopy("00000644".getBytes(StandardCharsets.US_ASCII), 0, noTerminator, 100, 8); // 8 digits, no NUL
        reseal(noTerminator, 0);
        assertRejected(noTerminator, "octal");
        byte[] linkname = t.clone();
        linkname[157] = 'x';
        reseal(linkname, 0);
        assertRejected(linkname, "linkname");
        byte[] name = t.clone();
        name[10] = 'x'; // data after the NUL terminator of the name
        reseal(name, 0);
        assertRejected(name, "name");
        byte[] trav = t.clone();
        trav[0] = '/';
        reseal(trav, 0);
        assertRejected(trav, "name");
        byte[] dirSize = t.clone();
        int dirHeader = 512 + 512; // hello.txt header + 1 data block
        dirSize[dirHeader + 124 + 10] = '1';
        reseal(dirSize, dirHeader);
        assertRejected(dirSize, "directory");
        byte[] emptyDirAsFile = t.clone();
        emptyDirAsFile[1024 + TarWriter.TYPE_OFF] = '0'; // the "dir/" header says regular file, size 0
        reseal(emptyDirAsFile, 1024);
        assertRejected(emptyDirAsFile, "ends with");
        byte[] slashFile = t.clone();
        slashFile[8] = '/'; // "hello.tx/"
        reseal(slashFile, 0);
        assertRejected(slashFile, "name");
    }

    @Test
    void structureDefectsRejected() {
        byte[] t = TarWriter.write(THREE);
        byte[] pad = t.clone();
        pad[512 + 9] = 1; // inside the padding of the first data block ("hello tar" is 9 bytes)
        assertRejected(pad, "padding");
        byte[] notBlocks = Arrays.copyOf(t, t.length + 1);
        assertRejected(notBlocks, "block");
        byte[] garbageAfterEnd = Arrays.copyOf(t, t.length + 512);
        garbageAfterEnd[t.length + 3] = 7;
        assertRejected(garbageAfterEnd, "end");
        byte[] zeroPad = Arrays.copyOf(t, 10240); // GNU tar pads to a 10240 record: accepted
        assertEquals(THREE, TarReader.read(zeroPad));
        byte[] oneZeroBlock = Arrays.copyOf(t, t.length - 512);
        assertRejected(oneZeroBlock, "end");
        byte[] noEnd = Arrays.copyOf(t, t.length - 1024);
        assertRejected(noEnd, "end");
        byte[] hugeSize = t.clone();
        System.arraycopy("77777777777\0".getBytes(StandardCharsets.US_ASCII), 0, hugeSize, 124, 12);
        reseal(hugeSize, 0);
        assertRejected(hugeSize, "truncated");
    }

    @Test
    void duplicateAndTreeConflictsRejected() {
        byte[] t = TarWriter.write(List.of(TestData.file("aa", "1"), TestData.file("bb", "2")));
        assertEquals(2, TarReader.read(t).size());
        byte[] dup = t.clone();
        int second = 1024;
        dup[second] = 'a';
        dup[second + 1] = 'a';
        reseal(dup, second);
        ArchiveException ex = assertThrows(ArchiveException.class, () -> TarReader.read(dup));
        assertTrue(ex.getMessage().contains("duplicate"), ex.getMessage());
    }

    @Test
    void everyTruncationRejected() {
        byte[] t = TarWriter.write(THREE);
        for (int n = 0; n < t.length; n++) {
            int len = n;
            byte[] cut = Arrays.copyOf(t, len);
            assertThrows(ArchiveException.class, () -> TarReader.read(cut), "truncated to " + len);
        }
    }

    @Test
    void everyByteFlipRejectedExceptFileData() {
        byte[] t = TarWriter.write(THREE);
        // layout: hello header 0..511, its data 512..520 (9 bytes) + padding; dir header 1024; big header 1536;
        // big data 2048..3047 (1000 bytes) + padding to 3071; two zero blocks 3072..4095
        for (int i = 0; i < t.length; i++) {
            boolean data = (i >= 512 && i < 521) || (i >= 2048 && i < 3048);
            for (int bit : new int[] {0, 6}) {
                byte[] f = t.clone();
                f[i] ^= (byte) (1 << bit);
                if (data) { // tar has no data checksum: accepted, and exactly this byte differs
                    List<ArchiveEntry> got = TarReader.read(f);
                    int differing = 0;
                    for (int k = 0; k < THREE.size(); k++) {
                        byte[] x = THREE.get(k).data();
                        byte[] y = got.get(k).data();
                        assertEquals(x.length, y.length);
                        for (int j = 0; j < x.length; j++) {
                            differing += x[j] != y[j] ? 1 : 0;
                        }
                    }
                    assertEquals(1, differing, "flip " + i);
                } else {
                    int at = i;
                    assertThrows(ArchiveException.class, () -> TarReader.read(f), "flip byte " + at + " bit " + bit + " must be rejected");
                }
            }
        }
    }
}
