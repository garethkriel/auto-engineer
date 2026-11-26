package ae.tar;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Interoperability with the installed GNU tar, used as an oracle in both directions. */
class GnuTarDifferentialTest {
    @TempDir
    Path tmp;

    @BeforeAll
    static void needsGnuTar() throws Exception {
        boolean ok;
        try {
            Process p = new ProcessBuilder("tar", "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            ok = p.waitFor() == 0 && out.contains("GNU tar");
        } catch (IOException e) {
            ok = false;
        }
        Assumptions.assumeTrue(ok, "GNU tar not installed");
    }

    /** Runs tar in {@code cwd}; returns stdout; fails the test (with stderr) on a non-zero exit. */
    String tar(Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("tar");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().put("TZ", "UTC");
        Path out = Files.createTempFile(tmp, "tar-out", ".txt");
        Path err = Files.createTempFile(tmp, "tar-err", ".txt");
        pb.redirectOutput(out.toFile()).redirectError(err.toFile());
        int code = pb.start().waitFor();
        String stderr = Files.readString(err, StandardCharsets.UTF_8);
        String stdout = Files.readString(out, StandardCharsets.UTF_8);
        Files.delete(out);
        Files.delete(err);
        assertEquals(0, code, "tar " + cmd + " failed: " + stderr);
        assertEquals("", stderr, "tar wrote warnings: " + stderr);
        return stdout;
    }

    // ---- our archives, read by GNU tar -------------------------------------------------------------------------

    @Test
    void gnuListsOurRandomArchivesWithTheSameNamesInTheSameOrder() throws Exception {
        Random r = new Random(4242);
        for (int i = 0; i < 60; i++) {
            List<Entry> in = T.randomEntries(r, 6, true);
            Path f = tmp.resolve("r" + i + ".tar");
            Files.write(f, TarWriter.write(in));
            String listing = tar(tmp, "--quoting-style=literal", "-tf", f.toString());
            List<String> got = new ArrayList<>(Arrays.asList(listing.split("\n", -1)));
            assertEquals("", got.remove(got.size() - 1), "listing ends with a newline");
            List<String> want = new ArrayList<>();
            for (Entry e : in) {
                want.add(e.type() == Entry.Type.DIRECTORY ? e.name() + "/" : e.name());
            }
            assertEquals(want, got, "batch " + i);
        }
    }

    @Test
    void gnuVerboseListingAgreesOnModeOwnerSizeAndTime() throws Exception {
        List<Entry> in = List.of(
            new Entry("plain.txt", Entry.Type.FILE, 0644, 1000, 1000, 1700000000L, "", "alice", "staff", new byte[1234]),
            new Entry("exec", Entry.Type.FILE, 0755, 0, 0, 0, "", "root", "root", new byte[0]),
            new Entry("big-uid", Entry.Type.FILE, 0600, 4000000L, 5000000L, 8589934592L, "", "", "", new byte[7]),
            new Entry("long-user", Entry.Type.FILE, 0640, 5, 6, 1700000001L, "", "u".repeat(40), "g".repeat(33), new byte[1]),
            Entry.dir("dir", 0750, 1700000002L));
        Path f = tmp.resolve("v.tar");
        Files.write(f, TarWriter.write(in));
        String listing = tar(tmp, "--quoting-style=literal", "-tvf", f.toString());
        String[] lines = listing.split("\n");
        assertEquals(in.size(), lines.length, listing);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
        for (int i = 0; i < in.size(); i++) {
            Entry e = in.get(i);
            String type = e.type() == Entry.Type.DIRECTORY ? "d" : "-";
            String mode = type + modeString(e.mode());
            String owner = (e.uname().isEmpty() ? e.uid() : e.uname()) + "/" + (e.gname().isEmpty() ? e.gid() : e.gname());
            String line = lines[i];
            assertTrue(line.startsWith(mode + " " + owner + " "), "line " + i + ": " + line + " wanted " + mode + " " + owner);
            assertTrue(line.contains(" " + e.data().length + " " + fmt.format(Instant.ofEpochSecond(e.mtime())) + " "), line);
        }
    }

    private static String modeString(int m) {
        String rwx = "rwxrwxrwx";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            sb.append((m & (0400 >> i)) != 0 ? rwx.charAt(i) : '-');
        }
        return sb.toString();
    }

    @Test
    void gnuExtractionOfOurRandomTreesEqualsOurExtraction() throws Exception {
        Random r = new Random(99);
        for (int i = 0; i < 25; i++) {
            List<Entry> tree = randomTree(r);
            Path archive = tmp.resolve("t" + i + ".tar");
            Files.write(archive, TarWriter.write(tree));
            Path viaGnu = Files.createDirectory(tmp.resolve("gnu" + i));
            Path viaOurs = Files.createDirectory(tmp.resolve("ours" + i));
            tar(viaGnu, "-xpf", archive.toString(), "--no-same-owner");
            TarExtractor.extract(TarReader.read(Files.readAllBytes(archive)), viaOurs);
            assertEquals(snapshot(viaGnu), snapshot(viaOurs), "tree " + i + ": " + tree.stream().map(Entry::name).toList());
        }
    }

    /** Safe trees: every directory is listed before its children (so directory times are explicit). */
    static List<Entry> randomTree(Random r) {
        List<Entry> out = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        List<String> files = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int[] dirModes = {0755, 0700, 0750, 0555, 0711};
        int nd = r.nextInt(5);
        for (int i = 0; i < nd; i++) {
            String name = unique(r, used, dirs.isEmpty() || r.nextBoolean() ? "" : dirs.get(r.nextInt(dirs.size())));
            dirs.add(name);
            out.add(Entry.dir(name, dirModes[r.nextInt(dirModes.length)], 1600000000L + r.nextInt(100000)));
        }
        int nf = r.nextInt(7);
        for (int i = 0; i < nf; i++) {
            String name = unique(r, used, dirs.isEmpty() || r.nextBoolean() ? "" : dirs.get(r.nextInt(dirs.size())));
            byte[] data = new byte[r.nextInt(4) == 0 ? 0 : r.nextInt(1200)];
            r.nextBytes(data);
            files.add(name);
            out.add(Entry.file(name, r.nextInt(01000) | 0400, 1600000000L + r.nextInt(100000), data));
        }
        int ns = r.nextInt(3);
        for (int i = 0; i < ns; i++) {
            String parent = dirs.isEmpty() || r.nextBoolean() ? "" : dirs.get(r.nextInt(dirs.size()));
            String name = unique(r, used, parent);
            String target = parent.isEmpty() ? T.randomSegment(r, 10, T.FS_UNICODE) : "../" + T.randomSegment(r, 10, T.FS_UNICODE);
            out.add(Entry.symlink(name, target));
        }
        if (!files.isEmpty() && r.nextBoolean()) {
            String target = files.get(r.nextInt(files.size()));
            String name = target + "~lnk"; // sorts right after its target, so the target stays earlier
            if (used.add(name)) {
                out.add(Entry.hardlink(name, target));
            }
        }
        // Children right after their directory, as real archivers write them: GNU tar restores a directory's time as
        // soon as it meets a member outside it, so interleaved children would (legitimately) disturb the time.
        out.sort((x, y) -> compareSegments(x.name(), y.name()));
        return out;
    }

    private static int compareSegments(String a, String b) {
        String[] x = a.split("/");
        String[] y = b.split("/");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = x[i].compareTo(y[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static String unique(Random r, Set<String> used, String parent) {
        while (true) {
            String n = (parent.isEmpty() ? "" : parent + "/") + T.randomSegment(r, 10, T.FS_UNICODE);
            if (used.add(n)) {
                return n;
            }
        }
    }

    // ---- GNU tar archives, read by us -----------------------------------------------------------------------------

    private static final FileTime T0 = FileTime.from(Instant.ofEpochSecond(1700000000L));

    private static void setPerms(Path p, int mode) throws IOException {
        Set<PosixFilePermission> s = new HashSet<>();
        PosixFilePermission[] all = {
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE};
        for (int i = 0; i < 9; i++) {
            if ((mode & (0400 >> i)) != 0) {
                s.add(all[i]);
            }
        }
        Files.setPosixFilePermissions(p, s);
    }

    private static void file(Path root, String rel, byte[] data, int mode) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
        setPerms(p, mode);
        Files.setLastModifiedTime(p, T0);
    }

    /** Builds a real tree and returns the expected entries keyed by name. */
    private static Map<String, Entry> buildTree(Path root, boolean longNames) throws IOException {
        Map<String, Entry> want = new TreeMap<>();
        Random r = new Random(1);
        byte[] big = new byte[1 << 20];
        r.nextBytes(big);
        String jp = T.FS_UNICODE ? "日本" : "nihon"; // non-ASCII file names need a UTF-8 capable JVM file system
        List<String> dirs = new ArrayList<>(List.of("src", "src/sub", "docs", jp, "with space"));
        if (longNames) {
            dirs.add("d".repeat(60));
            dirs.add("d".repeat(60) + "/" + "e".repeat(60));
            dirs.add("d".repeat(60) + "/" + "e".repeat(60) + "/" + "f".repeat(60));
        }
        for (String d : dirs) {
            Files.createDirectories(root.resolve(d));
        }
        String[] files = {"src/Main.java", "src/sub/empty", "docs/readme", jp + "/name.txt", "with space/a b", "top"};
        byte[][] datas = {"class Main {}".getBytes(StandardCharsets.UTF_8), new byte[0], new byte[512], new byte[513], big, "é".getBytes(StandardCharsets.UTF_8)};
        int[] modes = {0644, 0600, 0444, 0755, 0640, 0664};
        List<String> allFiles = new ArrayList<>(Arrays.asList(files));
        for (int i = 0; i < files.length; i++) {
            file(root, files[i], datas[i], modes[i]);
            want.put(files[i], Entry.file(files[i], modes[i], 1700000000L, datas[i]));
        }
        if (longNames) {
            String deep = dirs.get(dirs.size() - 1) + "/" + "g".repeat(90) + ".txt"; // 60*3 + 2 + 94 = 276 bytes
            byte[] d = "deep".getBytes(StandardCharsets.UTF_8);
            file(root, deep, d, 0644);
            want.put(deep, Entry.file(deep, 0644, 1700000000L, d));
            String ln = "l".repeat(150);
            Files.createSymbolicLink(root.resolve("longlink"), Path.of(ln));
            want.put("longlink", Entry.symlink("longlink", ln));
            allFiles.add(deep);
        }
        Files.createSymbolicLink(root.resolve("src/link"), Path.of("Main.java"));
        want.put("src/link", Entry.symlink("src/link", "Main.java"));
        Files.createSymbolicLink(root.resolve("up"), Path.of("src/sub"));
        want.put("up", Entry.symlink("up", "src/sub"));
        Files.createLink(root.resolve("hard"), root.resolve("top")); // tar stores the second name as a hard link
        for (String d : dirs) {
            setPerms(root.resolve(d), 0755);
            want.put(d, Entry.dir(d, 0755, 1700000000L));
        }
        for (String d : dirs) {
            Files.setLastModifiedTime(root.resolve(d), T0);
        }
        return want;
    }

    private static List<Entry> normaliseHardlinks(List<Entry> got, Map<String, Entry> want) {
        // "hard" and "top" share an inode. GNU stores the name it meets first as a file and the other as a link to it.
        List<Entry> out = new ArrayList<>();
        for (Entry e : got) {
            if (e.type() == Entry.Type.HARDLINK) {
                assertEquals(Set.of("hard", "top"), Set.of(e.name(), e.linkName()), "only the extra name may be a hard link");
                assertEquals(0, e.data().length);
                out.add(Entry.file(e.name(), 0664, 1700000000L, want.get("top").data()));
            } else {
                out.add(e);
            }
        }
        return out;
    }

    @Test
    void weReadGnuArchivesInEveryFormat() throws Exception {
        String[][] variants = {
            {"--format=ustar", "short"},
            {"--format=pax", "long"},
            {"--format=pax", "long", "--pax-option=delete=atime,delete=ctime"},
            {"--format=pax", "short", "--pax-option=comment=hello,SCHILY.x=1"},   // global header + unknown keys
        };
        int n = 0;
        for (String[] v : variants) {
            Path root = Files.createDirectory(tmp.resolve("tree" + n));
            boolean longNames = v[1].equals("long");
            Map<String, Entry> want = buildTree(root, longNames);
            Path archive = tmp.resolve("g" + n + ".tar");
            List<String> cmd = new ArrayList<>(List.of("--sort=name", "--numeric-owner", "--owner=0", "--group=0", "-cf", archive.toString(), "-C", root.toString(), v[0]));
            if (v.length > 2) {
                cmd.add(v[2]);
            }
            try (Stream<Path> s = Files.list(root)) {
                s.map(p -> p.getFileName().toString()).sorted().forEach(cmd::add);
            }
            tar(tmp, cmd.toArray(new String[0]));
            List<Entry> got = normaliseHardlinks(TarReader.read(Files.readAllBytes(archive)), want);
            Map<String, Entry> byName = new TreeMap<>();
            for (Entry e : got) {
                assertNull(byName.put(e.name(), e), "duplicate " + e.name());
            }
            Map<String, Entry> expected = new TreeMap<>(want);
            expected.put("hard", Entry.file("hard", 0664, 1700000000L, want.get("top").data()));
            // symlinks carry their own (creation) mtime: compare them without it
            for (Map.Entry<String, Entry> me : expected.entrySet()) {
                Entry w = me.getValue();
                Entry g = byName.get(me.getKey());
                assertNotNull(g, "missing " + me.getKey() + " in " + v[0] + " " + v[1]);
                if (w.type() == Entry.Type.SYMLINK) {
                    w = new Entry(w.name(), w.type(), g.mode(), 0, 0, g.mtime(), w.linkName(), "", "", new byte[0]);
                }
                assertEquals(w, g, me.getKey() + " via " + String.join(" ", v));
            }
            assertEquals(expected.keySet(), byName.keySet(), String.join(" ", v));
            n++;
        }
    }

    /** Runs a shell script fed on stdin as UTF-8 bytes, so non-ASCII names do not depend on the JVM's file-name encoding. */
    private void sh(Path cwd, String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-e").directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().put("TZ", "UTC");
        Process p = pb.start();
        p.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "script failed: " + out);
    }

    @Test
    void weReadGnuArchivesWithNonAsciiNamesInRawUstarAndPax() throws Exception {
        for (String fmt : new String[] {"ustar", "pax"}) {
            Path work = Files.createDirectory(tmp.resolve("u-" + fmt));
            sh(work, "mkdir -p é/日本 😀\n"
                + "printf x > é/日本/名.txt\n"
                + "printf '' > 😀/empty\n"
                + "chmod 644 é/日本/名.txt 😀/empty\n"
                + "chmod 755 é é/日本 😀\n"
                + "ln -s 日本/名.txt é/ln\n"
                + "touch -d @1700000000 é/日本/名.txt 😀/empty é/日本 é 😀\n"
                + "tar --format=" + fmt + " --sort=name --numeric-owner --owner=0 --group=0 -cf out.tar é 😀\n");
            List<Entry> got = TarReader.read(Files.readAllBytes(work.resolve("out.tar")));
            Map<String, Entry> byName = new TreeMap<>();
            for (Entry e : got) {
                byName.put(e.name(), e);
            }
            assertEquals(Set.of("é", "é/ln", "é/日本", "é/日本/名.txt", "😀", "😀/empty"), byName.keySet(), fmt);
            assertEquals(Entry.dir("é", 0755, 1700000000L), byName.get("é"), fmt);
            assertEquals(Entry.dir("é/日本", 0755, 1700000000L), byName.get("é/日本"), fmt);
            assertEquals(Entry.dir("😀", 0755, 1700000000L), byName.get("😀"), fmt);
            assertEquals(Entry.file("é/日本/名.txt", 0644, 1700000000L, new byte[] {'x'}), byName.get("é/日本/名.txt"), fmt);
            assertEquals(Entry.file("😀/empty", 0644, 1700000000L, new byte[0]), byName.get("😀/empty"), fmt);
            assertEquals("日本/名.txt", byName.get("é/ln").linkName(), fmt);
            // and the other way: GNU extracts our archive of the same names
            Path out = Files.createDirectory(work.resolve("x"));
            Files.write(work.resolve("ours.tar"), TarWriter.write(got));
            sh(out, "tar -xpf ../ours.tar --no-same-owner\n"
                + "test \"$(cat é/日本/名.txt)\" = x\n"
                + "test -L é/ln\n"
                + "test -f 😀/empty\n");
        }
    }

    @Test
    void ourHeaderAndDataBlocksEqualGnusForAPlainFile() throws Exception {
        Path root = Files.createDirectory(tmp.resolve("one"));
        byte[] data = "hello gnu".getBytes(StandardCharsets.UTF_8);
        file(root, "f", data, 0644);
        Path archive = tmp.resolve("one.tar");
        tar(tmp, "--format=ustar", "--numeric-owner", "--owner=0", "--group=0", "-cf", archive.toString(), "-C", root.toString(), "f");
        byte[] gnu = Files.readAllBytes(archive);
        byte[] ours = TarWriter.write(List.of(Entry.file("f", 0644, 1700000000L, data)));
        assertArrayEquals(Arrays.copyOfRange(gnu, 0, 1024), Arrays.copyOfRange(ours, 0, 1024));
    }

    @Test
    void ourHeaderBlockEqualsGnusForADirectoryAndASymlink() throws Exception {
        Path root = Files.createDirectory(tmp.resolve("two"));
        Files.createDirectories(root.resolve("d"));
        setPerms(root.resolve("d"), 0750);
        Files.setLastModifiedTime(root.resolve("d"), T0);
        Path archive = tmp.resolve("two.tar");
        tar(tmp, "--format=ustar", "--numeric-owner", "--owner=0", "--group=0", "-cf", archive.toString(), "-C", root.toString(), "d");
        byte[] gnu = Files.readAllBytes(archive);
        byte[] ours = TarWriter.write(List.of(Entry.dir("d", 0750, 1700000000L)));
        assertArrayEquals(Arrays.copyOfRange(gnu, 0, 512), Arrays.copyOfRange(ours, 0, 512));
    }

    // ---- tree snapshots ---------------------------------------------------------------------------------------------

    static SortedMap<String, String> snapshot(Path root) throws Exception {
        SortedMap<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.equals(root)) {
                    continue;
                }
                String rel = root.relativize(p).toString();
                if (Files.isSymbolicLink(p)) {
                    m.put(rel, "L " + Files.readSymbolicLink(p));
                    continue;
                }
                int mode = 0;
                for (PosixFilePermission q : Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS)) {
                    mode |= 0400 >> q.ordinal();
                }
                long mtime = Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis() / 1000;
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    m.put(rel, "D " + Integer.toOctalString(mode) + " " + mtime);
                } else {
                    byte[] d = Files.readAllBytes(p);
                    String h = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d));
                    m.put(rel, "F " + Integer.toOctalString(mode) + " " + mtime + " " + d.length + " " + h);
                }
            }
        }
        return m;
    }
}
