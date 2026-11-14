package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SnapshotTest {
    @TempDir Path tmp;

    private Path put(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    @Test
    void keysAreRelativeSlashPathsAndEmptyDirsIgnored() throws IOException {
        put(tmp, "a.txt", "A");
        put(tmp, "sub/deep/b.txt", "B");
        Files.createDirectories(tmp.resolve("empty"));
        Snapshot s = Snapshot.of(tmp);
        assertEquals(List.of("a.txt", "sub/deep/b.txt"), List.copyOf(s.hashes().keySet()));
    }

    @Test
    void knownSha256() throws IOException {
        put(tmp, "x", "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Snapshot.of(tmp).hashes().get("x"));
    }

    @Test
    void emptyFileHashesToEmptyDigest() throws IOException {
        put(tmp, "e", "");
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Snapshot.of(tmp).hashes().get("e"));
    }

    @Test
    void ignoreGlobsSkipMatches() throws IOException {
        put(tmp, "keep.txt", "1");
        put(tmp, "skip.log", "2");
        put(tmp, "build/out.txt", "3");
        Snapshot s = Snapshot.of(tmp, "*.log", "build/**");
        assertEquals(List.of("keep.txt"), List.copyOf(s.hashes().keySet()));
    }

    @Test
    void symlinksAreSkipped() throws IOException {
        Path real = put(tmp, "real.txt", "R");
        try {
            Files.createSymbolicLink(tmp.resolve("link.txt"), real);
        } catch (UnsupportedOperationException | IOException e) {
            return; // platform cannot create symlinks; nothing to verify
        }
        assertEquals(List.of("real.txt"), List.copyOf(Snapshot.of(tmp).hashes().keySet()));
    }

    @Test
    void invalidDirectoriesRejected() throws IOException {
        Path file = put(tmp, "f", "x");
        assertThrows(IllegalArgumentException.class, () -> Snapshot.of(null));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.of(tmp.resolve("nope")));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.of(file));
    }

    @Test
    void tabInFileNameRejected() throws IOException {
        try {
            put(tmp, "a\tb", "x");
        } catch (java.nio.file.InvalidPathException e) {
            return;
        }
        assertThrows(IllegalArgumentException.class, () -> Snapshot.of(tmp));
    }

    @Test
    void diffFindsAddedRemovedModified() throws IOException {
        Path before = Files.createDirectory(tmp.resolve("before"));
        Path after = Files.createDirectory(tmp.resolve("after"));
        put(before, "same.txt", "s");
        put(after, "same.txt", "s");
        put(before, "gone.txt", "g");
        put(before, "chg.txt", "old");
        put(after, "chg.txt", "new");
        put(after, "dir/new.txt", "n");
        Diff d = Snapshot.of(before).diff(Snapshot.of(after));
        assertEquals(List.of("dir/new.txt"), d.added());
        assertEquals(List.of("gone.txt"), d.removed());
        assertEquals(List.of("chg.txt"), d.modified());
        assertFalse(d.isEmpty());
        assertEquals("+ dir/new.txt\n- gone.txt\n~ chg.txt", d.render());
    }

    @Test
    void identicalTreesGiveEmptyDiff() throws IOException {
        put(tmp, "a", "1");
        Snapshot s = Snapshot.of(tmp);
        Diff d = s.diff(Snapshot.of(tmp));
        assertTrue(d.isEmpty());
        assertEquals("no changes", d.render());
        assertEquals(s, Snapshot.of(tmp));
        assertEquals(s.hashCode(), Snapshot.of(tmp).hashCode());
    }

    @Test
    void diffListsAreImmutable() {
        Diff d = new Snapshot(java.util.Map.of()).diff(new Snapshot(java.util.Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> d.added().add("x"));
    }

    @Test
    void writeThenReadRoundTrips() throws IOException {
        put(tmp, "data/a.txt", "A");
        put(tmp, "data/b c.txt", "B");
        Snapshot s = Snapshot.of(tmp.resolve("data"));
        Path file = tmp.resolve("snap.tsv");
        s.write(file);
        assertEquals(s, Snapshot.read(file));
        // no temp files left behind
        try (var stream = Files.list(tmp)) {
            assertEquals(List.of("data", "snap.tsv"),
                    stream.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void writeOverwritesExistingFile() throws IOException {
        Path file = tmp.resolve("snap.tsv");
        Files.writeString(file, "garbage");
        new Snapshot(java.util.Map.of("a", "0".repeat(64))).write(file);
        assertEquals(java.util.Map.of("a", "0".repeat(64)), Snapshot.read(file).hashes());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "nohashnotab",
        "zzzz\tfile.txt",
        "0000\tfile.txt",
        "0000000000000000000000000000000000000000000000000000000000000000\t",
    })
    void readRejectsMalformedLines(String line) throws IOException {
        Path file = tmp.resolve("bad.tsv");
        Files.writeString(file, line + "\n");
        assertThrows(IOException.class, () -> Snapshot.read(file));
    }

    @Test
    void readRejectsDuplicatePaths() throws IOException {
        String h = "0".repeat(64);
        Path file = tmp.resolve("dup.tsv");
        Files.writeString(file, h + "\ta\n" + h + "\ta\n");
        assertThrows(IOException.class, () -> Snapshot.read(file));
    }

    @Test
    void readOfEmptyFileIsEmptySnapshot() throws IOException {
        Path file = tmp.resolve("empty.tsv");
        Files.writeString(file, "");
        assertTrue(Snapshot.read(file).hashes().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "a.txt, *.txt, true",
        "dir/a.txt, *.txt, false",
        "dir/a.txt, **/*.txt, true",
        "a.log, *.txt, false"
    })
    void globSemantics(String rel, String glob, boolean ignored) throws IOException {
        put(tmp, rel, "x");
        boolean present = Snapshot.of(tmp, glob).hashes().containsKey(rel);
        assertEquals(!ignored, present);
    }
}
