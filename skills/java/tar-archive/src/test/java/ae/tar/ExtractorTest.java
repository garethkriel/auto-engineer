package ae.tar;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractorTest {
    @TempDir
    Path tmp;
    Path dest;

    @BeforeEach
    void setUp() throws IOException {
        dest = Files.createDirectory(tmp.resolve("out"));
    }

    private static String text(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private void rejects(String messagePart, Entry... es) {
        TarFormatException e = assertThrows(TarFormatException.class, () -> TarExtractor.extract(List.of(es), dest));
        assertTrue(e.getMessage().toLowerCase().contains(messagePart.toLowerCase()), "wanted '" + messagePart + "' in: " + e.getMessage());
    }

    private long countEntries(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            return s.count();
        }
    }

    @Test
    void extractsEveryKindWithModesAndTimes() throws Exception {
        TarExtractor.extract(List.of(
            Entry.dir("d", 0755, 1700000001L),
            Entry.file("d/f", 0640, 1700000002L, "hello".getBytes(StandardCharsets.UTF_8)),
            Entry.symlink("d/l", "f"),
            Entry.hardlink("d/h", "d/f"),
            Entry.dir("ro", 0555, 1700000003L),
            Entry.file("ro/inside", 0444, 1700000004L, new byte[] {1, 2, 3})), dest);
        assertEquals("hello", text(dest.resolve("d/f")));
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(dest.resolve("d/f"))));
        assertEquals(1700000002L, Files.getLastModifiedTime(dest.resolve("d/f")).toMillis() / 1000);
        assertTrue(Files.isSymbolicLink(dest.resolve("d/l")));
        assertEquals("f", Files.readSymbolicLink(dest.resolve("d/l")).toString());
        assertEquals("hello", text(dest.resolve("d/h")));
        assertFalse(Files.isSymbolicLink(dest.resolve("d/h")));
        // directory metadata is applied after the children were written
        assertEquals("r-xr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(dest.resolve("ro"))));
        assertEquals(1700000001L, Files.getLastModifiedTime(dest.resolve("d")).toMillis() / 1000);
        assertEquals(1700000003L, Files.getLastModifiedTime(dest.resolve("ro")).toMillis() / 1000);
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(dest.resolve("ro/inside")));
        Files.setPosixFilePermissions(dest.resolve("ro"), PosixFilePermissions.fromString("rwxr-xr-x")); // let @TempDir clean up
    }

    @Test
    void specialModeBitsAreDropped() throws Exception {
        TarExtractor.extract(List.of(Entry.file("s", 06755, 0, new byte[0])), dest);
        Set<PosixFilePermission> p = Files.getPosixFilePermissions(dest.resolve("s"));
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(p));
    }

    @Test
    void missingParentsAreCreated() throws Exception {
        TarExtractor.extract(List.of(T.f("a/b/c", "x")), dest);
        assertEquals("x", text(dest.resolve("a/b/c")));
    }

    @Test
    void duplicatesAndKindConflictsAreRejected() throws Exception {
        rejects("duplicate", T.f("a", "1"), T.f("a", "2"));
        rejects("duplicate", T.f("a", "1"), Entry.dir("a", 0755, 0));
        rejects("duplicate", Entry.symlink("a", "x"), T.f("a", "1"));
        rejects("conflict", T.f("a", "1"), T.f("a/b", "2"));            // a file is not a directory
        rejects("conflict", Entry.symlink("a", "x"), T.f("a/b", "2"));  // nor is a link, even a harmless one
        rejects("conflict", T.f("a/b", "2"), T.f("a", "1"));            // the other order: "a" is already a parent
        rejects("conflict", T.f("a/b", "2"), Entry.symlink("a", "x"));
        assertEquals(0, countEntries(dest) - 1, "nothing written");
    }

    @Test
    void symlinkTargetsMustStayInside() throws Exception {
        rejects("unsafe", Entry.symlink("l", "/etc/passwd"));
        rejects("unsafe", Entry.symlink("l", "../x"));
        rejects("unsafe", Entry.symlink("l", ".."));
        rejects("unsafe", Entry.symlink("d/l", "../../x"));            // d is one level deep: two ups escape
        rejects("unsafe", Entry.symlink("d/l", "./../../x"));
        // ".." after a name is refused even when it cancels out: "s" -> "." plus "t" -> "s/.." would leave dest
        rejects("unsafe", Entry.symlink("n", "d/.."));
        rejects("unsafe", Entry.symlink("d/m", "a/../b"));
        rejects("unsafe", Entry.symlink("s", "."), Entry.symlink("t", "s/.."));
        // controls: one level up from d; dot segments; names only; a trailing slash
        TarExtractor.extract(List.of(Entry.dir("d", 0755, 0), Entry.symlink("d/l", "../f"), Entry.symlink("d/p", "./../f"),
            Entry.symlink("o", "."), Entry.symlink("q", "d/x/"), Entry.symlink("r", "a//b")), dest);
        for (String n : List.of("d/l", "d/p", "o", "q", "r")) {
            assertTrue(Files.isSymbolicLink(dest.resolve(n)), n);
        }
    }

    @Test
    void writingThroughAnArchiveSymlinkIsRejected() throws Exception {
        rejects("conflict", Entry.symlink("link", "sub"), T.f("link/x", "boom"));
        rejects("conflict", Entry.symlink("link", "sub"), Entry.dir("link/sub", 0755, 0));
    }

    @Test
    void preExistingSymlinkOnDiskIsNeverFollowed() throws Exception {
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Files.createSymbolicLink(dest.resolve("evil"), outside);
        rejects("symlink", T.f("evil/x", "boom"));
        assertEquals(0, countEntries(outside) - 1, "nothing may be written outside");
        rejects("exists", T.f("evil", "boom"));                        // final component is the link itself
        rejects("exists", Entry.symlink("evil", "x"));
        rejects("symlink", Entry.dir("evil", 0755, 0));                // an existing link is not an existing directory
    }

    @Test
    void danglingPreExistingSymlinkIsAlsoNeverFollowed() throws Exception {
        Files.createSymbolicLink(dest.resolve("dangling"), tmp.resolve("outside-that-does-not-exist"));
        rejects("symlink", T.f("dangling/x", "boom"));
        rejects("exists", T.f("dangling", "boom"));
        assertFalse(Files.exists(tmp.resolve("outside-that-does-not-exist"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void timesOutsideTheSupportedRangeAreNotApplied() throws Exception {
        TarExtractor.extract(List.of(Entry.file("neg", 0644, -5, new byte[0]), Entry.file("huge", 0644, Long.MAX_VALUE, new byte[0])), dest);
        long now = System.currentTimeMillis();
        for (String n : List.of("neg", "huge")) {
            long t = Files.getLastModifiedTime(dest.resolve(n)).toMillis();
            assertTrue(Math.abs(now - t) < 60_000, n + " keeps the extraction time, got " + t);
        }
    }

    @Test
    void nestedDirectoriesGetTheirMetadataInnermostFirst() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
            "root ignores directory permissions, so the order cannot be observed");
        TarExtractor.extract(List.of(Entry.dir("p", 0600, 1), Entry.dir("p/c", 0700, 2)), dest);
        Files.setPosixFilePermissions(dest.resolve("p"), PosixFilePermissions.fromString("rwx------"));
        assertEquals(2, Files.getLastModifiedTime(dest.resolve("p/c")).toMillis() / 1000);
    }

    @Test
    void preExistingFilesAreNotOverwritten() throws Exception {
        Files.writeString(dest.resolve("f"), "old");
        rejects("exists", T.f("f", "new"));
        assertEquals("old", text(dest.resolve("f")));
        rejects("not a directory", T.f("f/x", "new"));
        rejects("not a directory", Entry.dir("f", 0755, 0));
    }

    @Test
    void preExistingDirectoryIsMerged() throws Exception {
        Files.createDirectory(dest.resolve("d"));
        Files.writeString(dest.resolve("d/keep"), "k");
        TarExtractor.extract(List.of(Entry.dir("d", 0700, 5), T.f("d/new", "n")), dest);
        assertEquals("k", text(dest.resolve("d/keep")));
        assertEquals("n", text(dest.resolve("d/new")));
    }

    @Test
    void hardlinkRules() throws Exception {
        rejects("hardlink", Entry.hardlink("h", "missing"));
        rejects("hardlink", Entry.hardlink("h", "f"), T.f("f", "later"));               // target must come first
        rejects("hardlink", Entry.dir("d", 0755, 0), Entry.hardlink("h", "d"));         // not to a directory
        rejects("hardlink", Entry.symlink("s", "x"), Entry.hardlink("h", "s"));         // not to a symlink
        TarExtractor.extract(List.of(T.f("f", "data"), Entry.hardlink("h1", "f"), Entry.hardlink("h2", "h1")), dest);
        assertEquals("data", text(dest.resolve("h1")));
        assertEquals("data", text(dest.resolve("h2")));
    }

    @Test
    void rejectedArchiveLeavesDestinationUntouched() throws Exception {
        rejects("unsafe", T.f("good", "x"), Entry.dir("gooddir", 0755, 0), T.f("gooddir/more", "y"), Entry.symlink("bad", "../x"));
        assertEquals(0, countEntries(dest) - 1);
        rejects("hardlink", T.f("good", "x"), Entry.hardlink("h", "nope"));
        assertEquals(0, countEntries(dest) - 1);
        Files.writeString(dest.resolve("taken"), "t");
        rejects("exists", T.f("a", "x"), T.f("taken", "y"));
        assertFalse(Files.exists(dest.resolve("a"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void destinationMustBeAnExistingDirectory() throws Exception {
        TarFormatException e = assertThrows(TarFormatException.class,
            () -> TarExtractor.extract(List.of(T.f("a", "x")), tmp.resolve("missing")));
        assertTrue(e.getMessage().contains("destination"), e.getMessage());
        Files.writeString(tmp.resolve("file"), "x");
        assertThrows(TarFormatException.class, () -> TarExtractor.extract(List.of(T.f("a", "x")), tmp.resolve("file")));
    }

    @Test
    void roundTripThroughArchiveBytes() throws Exception {
        String d = T.FS_UNICODE ? "é" : "e";
        String f = T.FS_UNICODE ? "日本" : "nihon";
        List<Entry> in = List.of(Entry.dir(d, 0755, 10), T.f(d + "/" + f, "x"), Entry.symlink(d + "/l", f));
        TarExtractor.extract(TarReader.read(TarWriter.write(in)), dest);
        assertEquals("x", text(dest.resolve(d + "/l")));
        FileTime t = Files.getLastModifiedTime(dest.resolve(d));
        assertEquals(10, t.toMillis() / 1000);
    }
}
