package rl;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RotatingLogTest {
    @TempDir Path dir;

    private String read(Path p) throws IOException {
        return Files.readString(p);
    }

    @Test
    void appendsLinesWithNewline() throws IOException {
        RotatingLog log = new RotatingLog(dir.resolve("a.log"), 100, 2);
        log.append("one");
        log.append("two");
        assertEquals("one\ntwo\n", read(dir.resolve("a.log")));
        assertEquals(8, log.currentSize());
        assertTrue(log.backups().isEmpty());
    }

    @Test
    void rotatesWhenLineWouldExceedLimit() throws IOException {
        Path f = dir.resolve("a.log");
        RotatingLog log = new RotatingLog(f, 8, 2);
        log.append("aaa"); // 4
        log.append("bbb"); // 8, exactly fits
        assertTrue(log.backups().isEmpty());
        log.append("ccc"); // rotate
        assertEquals("ccc\n", read(f));
        assertEquals("aaa\nbbb\n", read(dir.resolve("a.log.1")));
        assertEquals(List.of(dir.resolve("a.log.1")), log.backups());
    }

    @Test
    void dropsOldestBackupBeyondMaxBackups() throws IOException {
        Path f = dir.resolve("a.log");
        RotatingLog log = new RotatingLog(f, 4, 2);
        for (String s : List.of("111", "222", "333", "444")) log.append(s);
        assertEquals("444\n", read(f));
        assertEquals("333\n", read(dir.resolve("a.log.1")));
        assertEquals("222\n", read(dir.resolve("a.log.2")));
        assertFalse(Files.exists(dir.resolve("a.log.3")));
        assertEquals(List.of(dir.resolve("a.log.1"), dir.resolve("a.log.2")), log.backups());
    }

    @Test
    void zeroBackupsTruncates() throws IOException {
        Path f = dir.resolve("a.log");
        RotatingLog log = new RotatingLog(f, 4, 0);
        log.append("111");
        log.append("222");
        assertEquals("222\n", read(f));
        assertTrue(log.backups().isEmpty());
        assertFalse(Files.exists(dir.resolve("a.log.1")));
    }

    @Test
    void oversizedLineGoesAloneIntoFreshFile() throws IOException {
        Path f = dir.resolve("a.log");
        RotatingLog log = new RotatingLog(f, 4, 1);
        log.append("a");
        log.append("this is far too long");
        assertEquals("this is far too long\n", read(f));
        assertEquals("a\n", read(dir.resolve("a.log.1")));
        log.append("b");
        assertEquals("b\n", read(f));
        assertEquals("this is far too long\n", read(dir.resolve("a.log.1")));
    }

    @Test
    void oversizedLineIntoEmptyFileDoesNotRotate() throws IOException {
        RotatingLog log = new RotatingLog(dir.resolve("a.log"), 2, 1);
        log.append("long line");
        assertTrue(log.backups().isEmpty());
        assertEquals("long line\n", read(dir.resolve("a.log")));
    }

    @Test
    void countsUtf8BytesNotChars() throws IOException {
        RotatingLog log = new RotatingLog(dir.resolve("a.log"), 6, 1);
        log.append("é€"); // 2+3+1 = 6 bytes
        assertEquals(6, log.currentSize());
        log.append("x");
        assertEquals("é€\n", read(dir.resolve("a.log.1")));
    }

    @Test
    void honoursExistingFileAtConstruction() throws IOException {
        Path f = dir.resolve("a.log");
        Files.writeString(f, "old1\nold2\n"); // 10 bytes
        RotatingLog log = new RotatingLog(f, 12, 1);
        assertEquals(10, log.currentSize());
        log.append("new");
        assertEquals("old1\nold2\n", read(dir.resolve("a.log.1")));
        assertEquals("new\n", read(f));
    }

    @Test
    void leavesNoTempFiles() throws IOException {
        RotatingLog log = new RotatingLog(dir.resolve("a.log"), 4, 2);
        for (int i = 0; i < 10; i++) log.append("l" + i);
        try (Stream<Path> s = Files.list(dir)) {
            List<String> names = s.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("a.log", "a.log.1", "a.log.2"), names);
        }
    }

    @ParameterizedTest
    @CsvSource({"'a\nb'", "'a\rb'", "'\n'"})
    void rejectsLineBreaks(String line) {
        RotatingLog log = new RotatingLog(dir.resolve("a.log"), 10, 1);
        assertThrows(IllegalArgumentException.class, () -> log.append(line));
        assertEquals(0, log.currentSize());
    }

    @Test
    void rejectsNullLine() {
        RotatingLog log = new RotatingLog(dir.resolve("a.log"), 10, 1);
        assertThrows(NullPointerException.class, () -> log.append(null));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-5,1", "10,-1"})
    void rejectsBadLimits(long max, int backups) {
        Path f = dir.resolve("a.log");
        assertThrows(IllegalArgumentException.class, () -> new RotatingLog(f, max, backups));
    }

    @Test
    void rejectsNullPath() {
        assertThrows(NullPointerException.class, () -> new RotatingLog(null, 10, 1));
    }

    @Test
    void ioFailureIsUnchecked() {
        // parent directory does not exist
        RotatingLog log = new RotatingLog(dir.resolve("missing/a.log"), 10, 1);
        assertThrows(java.io.UncheckedIOException.class, () -> log.append("x"));
    }
}
