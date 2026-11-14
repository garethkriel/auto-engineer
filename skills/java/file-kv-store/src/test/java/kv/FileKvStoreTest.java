package kv;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileKvStoreTest {
    @TempDir Path dir;

    private Path file() { return dir.resolve("data.log"); }

    @Test
    void openCreatesMissingFile() throws IOException {
        FileKvStore s = FileKvStore.open(file());
        assertTrue(Files.exists(file()));
        assertEquals(0, s.size());
    }

    @Test
    void putGetRemove() throws IOException {
        FileKvStore s = FileKvStore.open(file());
        s.put("a", "1");
        s.put("a", "2");
        assertEquals(Optional.of("2"), s.get("a"));
        assertTrue(s.remove("a"));
        assertFalse(s.remove("a"));
        assertEquals(Optional.empty(), s.get("a"));
    }

    @Test
    void stateSurvivesReopen() throws IOException {
        FileKvStore s = FileKvStore.open(file());
        s.put("a", "1");
        s.put("b", "2");
        s.put("c", "3");
        s.remove("b");
        s.put("a", "9");
        FileKvStore r = FileKvStore.open(file());
        assertEquals(List.of("a", "c"), r.keys());
        assertEquals(Optional.of("9"), r.get("a"));
        assertEquals(Optional.of("3"), r.get("c"));
    }

    @Test
    void journalFormatIsExact() throws IOException {
        FileKvStore s = FileKvStore.open(file());
        s.put("k", "v");
        s.remove("k");
        assertEquals("S\tk\tv\nD\tk\n", Files.readString(file()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "tab\there", "line\nbreak", "cr\rhere", "back\\slash", "\\n literal", "", "\\", "a\t\n\\\r"})
    void specialCharactersRoundTrip(String value) throws IOException {
        FileKvStore s = FileKvStore.open(file());
        s.put("key" + value, value);
        FileKvStore r = FileKvStore.open(file());
        assertEquals(Optional.of(value), r.get("key" + value));
        assertEquals(List.of("key" + value), r.keys());
    }

    @Test
    void tornTrailingLineIsDroppedAndTruncated() throws IOException {
        Files.writeString(file(), "S\ta\t1\nS\tb\t2", StandardCharsets.UTF_8);
        FileKvStore s = FileKvStore.open(file());
        assertEquals(List.of("a"), s.keys());
        assertEquals("S\ta\t1\n", Files.readString(file()));
        s.put("c", "3");
        assertEquals("S\ta\t1\nS\tc\t3\n", Files.readString(file()));
    }

    @Test
    void tornLineWithoutAnyNewlineTruncatesToEmpty() throws IOException {
        Files.writeString(file(), "S\ta\t1");
        FileKvStore s = FileKvStore.open(file());
        assertEquals(0, s.size());
        assertEquals("", Files.readString(file()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X\ta\t1\n", "S\ta\n", "D\ta\t1\n", "S\ta\t1\t2\n", "\n", "S\ta\tbad\\q\n", "S\ta\tend\\\n"})
    void malformedCompleteLineThrowsWithLineNumber(String bad) throws IOException {
        Files.writeString(file(), "S\tok\t1\n" + bad);
        IOException e = assertThrows(IOException.class, () -> FileKvStore.open(file()));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void compactShrinksJournalAndKeepsState() throws IOException {
        FileKvStore s = FileKvStore.open(file());
        for (int i = 0; i < 50; i++) s.put("k", "v" + i);
        s.put("z", "1");
        s.put("b", "2");
        s.remove("z");
        s.compact();
        assertEquals("S\tb\t2\nS\tk\tv49\n", Files.readString(file()));
        s.put("n", "x");
        FileKvStore r = FileKvStore.open(file());
        assertEquals(List.of("b", "k", "n"), r.keys());
        try (var stream = Files.list(dir)) {
            assertEquals(1, stream.count(), "no temp file left behind");
        }
    }

    @Test
    void invalidArgumentsDoNotChangeState() throws IOException {
        FileKvStore s = FileKvStore.open(file());
        s.put("a", "1");
        assertThrows(NullPointerException.class, () -> s.put(null, "x"));
        assertThrows(NullPointerException.class, () -> s.put("x", null));
        assertThrows(NullPointerException.class, () -> s.remove(null));
        assertThrows(IllegalArgumentException.class, () -> s.put("", "x"));
        assertEquals(List.of("a"), s.keys());
        assertEquals("S\ta\t1\n", Files.readString(file()));
    }

    @Test
    void removingAbsentKeyWritesNothing() throws IOException {
        FileKvStore s = FileKvStore.open(file());
        s.remove("ghost");
        assertEquals("", Files.readString(file()));
    }

    @Test
    void keysViewIsUnmodifiable() throws IOException {
        FileKvStore s = FileKvStore.open(file());
        s.put("a", "1");
        assertThrows(UnsupportedOperationException.class, () -> s.keys().add("x"));
    }
}
