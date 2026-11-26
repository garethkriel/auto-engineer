package ae.arch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchivesTest {
    @Test
    void extractAndSnapshotRoundTrip(@TempDir Path dir) throws IOException {
        List<ArchiveEntry> in = TarTest.viewWithParents(TestData.random(11, 12, 1500, false));
        Path root = dir.resolve("r");
        Archives.extractTo(root, in);
        assertEquals(in, Archives.snapshot(root));
        for (ArchiveEntry e : in) {
            if (!e.directory()) {
                assertArrayEquals(e.data(), Files.readAllBytes(root.resolve(e.name())));
            }
        }
    }

    @Test
    void snapshotOrdersParentsBeforeChildren(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("b/c"));
        Files.writeString(dir.resolve("b/c/z"), "1");
        Files.writeString(dir.resolve("b/a"), "2");
        Files.writeString(dir.resolve("a"), "3");
        List<String> names = Archives.snapshot(dir).stream().map(ArchiveEntry::name).toList();
        assertEquals(List.of("a", "b/", "b/a", "b/c/", "b/c/z"), names);
    }

    @Test
    void extractRefusesToOverwrite(@TempDir Path dir) throws IOException {
        Archives.extractTo(dir, List.of(TestData.file("f", "1")));
        assertThrows(IOException.class, () -> Archives.extractTo(dir, List.of(TestData.file("f", "2"))));
        assertEquals("1", Files.readString(dir.resolve("f")));
    }

    @Test
    void extractChecksTreeBeforeWriting(@TempDir Path dir) {
        ArchiveEntry a = TestData.file("a", "1");
        assertThrows(ArchiveException.class, () -> Archives.extractTo(dir.resolve("x"), List.of(a, a)));
        assertFalse(Files.exists(dir.resolve("x")), "nothing may be written for an invalid tree");
    }

    @Test
    void zipAndTarAgree() {
        for (long seed = 0; seed < 10; seed++) {
            List<ArchiveEntry> in = TestData.random(seed, 8, 1200);
            assertEquals(ZipReader.read(ZipWriter.write(in, ZipWriter.Method.AUTO, "")), TarReader.read(TarWriter.write(in)));
        }
    }
}
