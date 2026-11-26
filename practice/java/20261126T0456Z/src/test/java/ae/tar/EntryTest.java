package ae.tar;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EntryTest {
    @ParameterizedTest
    @ValueSource(strings = {"a", "a/b", "dir/sub/file.txt", "é/ü", "a b", "a..b", ".hidden", "..x", "x..", "a/.b", "日本語/名前"})
    void acceptsSafeNames(String n) {
        assertEquals(n, T.f(n, "").name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/abs", "a/", "a//b", "./a", "a/./b", "a/.", ".", "..", "../a", "a/..", "a/../b", "a\0b", "\uD800"})
    void rejectsUnsafeNames(String n) {
        assertThrows(IllegalArgumentException.class, () -> T.f(n, ""), "name=" + n.replace("\0", "<NUL>"));
    }

    @Test
    void modeRange() {
        assertEquals(07777, Entry.file("a", 07777, 0, new byte[0]).mode());
        assertEquals(0, Entry.file("a", 0, 0, new byte[0]).mode());
        assertThrows(IllegalArgumentException.class, () -> Entry.file("a", 010000, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Entry.file("a", -1, 0, new byte[0]));
    }

    @Test
    void ownerIdsMustBeNonNegative() {
        assertThrows(IllegalArgumentException.class,
            () -> new Entry("a", Entry.Type.FILE, 0644, -1, 0, 0, "", "", "", new byte[0]));
        assertThrows(IllegalArgumentException.class,
            () -> new Entry("a", Entry.Type.FILE, 0644, 0, -1, 0, "", "", "", new byte[0]));
        assertEquals(Long.MAX_VALUE, new Entry("a", Entry.Type.FILE, 0644, Long.MAX_VALUE, 0, 0, "", "", "", new byte[0]).uid());
    }

    @Test
    void linkRules() {
        assertEquals("t", Entry.symlink("l", "t").linkName());
        assertThrows(IllegalArgumentException.class, () -> Entry.symlink("l", ""));
        assertThrows(IllegalArgumentException.class, () -> Entry.hardlink("l", ""));
        assertThrows(IllegalArgumentException.class, () -> Entry.hardlink("l", "../x")); // hardlink targets are names
        assertThrows(IllegalArgumentException.class,
            () -> new Entry("f", Entry.Type.FILE, 0644, 0, 0, 0, "t", "", "", new byte[0])); // file with a link name
        assertThrows(IllegalArgumentException.class,
            () -> new Entry("d", Entry.Type.DIRECTORY, 0755, 0, 0, 0, "t", "", "", new byte[0]));
        assertEquals("../up", Entry.symlink("l", "../up").linkName()); // symlink targets are free text
    }

    @Test
    void onlyFilesCarryData() {
        assertThrows(IllegalArgumentException.class,
            () -> new Entry("d", Entry.Type.DIRECTORY, 0755, 0, 0, 0, "", "", "", new byte[1]));
        assertThrows(IllegalArgumentException.class,
            () -> new Entry("l", Entry.Type.SYMLINK, 0777, 0, 0, 0, "t", "", "", new byte[1]));
    }

    @Test
    void textFieldsRejectNulAndLoneSurrogates() {
        assertThrows(IllegalArgumentException.class,
            () -> new Entry("a", Entry.Type.FILE, 0644, 0, 0, 0, "", "u\0", "", new byte[0]));
        assertThrows(IllegalArgumentException.class,
            () -> new Entry("a", Entry.Type.FILE, 0644, 0, 0, 0, "", "", "g\uDC00", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Entry.symlink("l", "t\0"));
    }

    @Test
    void dataIsCopiedOnTheWayInAndOut() {
        byte[] src = {1, 2, 3};
        Entry e = Entry.file("a", 0644, 0, src);
        src[0] = 9;
        assertEquals(1, e.data()[0]);
        e.data()[1] = 9;
        assertEquals(2, e.data()[1]);
    }

    @Test
    void equalityComparesDataByContentAndEveryField() {
        Entry a = Entry.file("a", 0644, 5, new byte[] {1, 2});
        assertEquals(a, Entry.file("a", 0644, 5, new byte[] {1, 2}));
        assertEquals(a.hashCode(), Entry.file("a", 0644, 5, new byte[] {1, 2}).hashCode());
        List<Entry> others = List.of(
            Entry.file("b", 0644, 5, new byte[] {1, 2}),
            Entry.file("a", 0600, 5, new byte[] {1, 2}),
            Entry.file("a", 0644, 6, new byte[] {1, 2}),
            Entry.file("a", 0644, 5, new byte[] {1, 3}),
            new Entry("a", Entry.Type.FILE, 0644, 1, 0, 5, "", "", "", new byte[] {1, 2}),
            new Entry("a", Entry.Type.FILE, 0644, 0, 1, 5, "", "", "", new byte[] {1, 2}),
            new Entry("a", Entry.Type.FILE, 0644, 0, 0, 5, "", "u", "", new byte[] {1, 2}),
            new Entry("a", Entry.Type.FILE, 0644, 0, 0, 5, "", "", "g", new byte[] {1, 2}));
        for (Entry o : others) {
            assertNotEquals(a, o, o.toString());
        }
        assertNotEquals(Entry.symlink("a", "t"), Entry.hardlink("a", "t"));
    }
}
