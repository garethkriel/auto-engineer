package ae.arch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CrcAndNamesTest {
    @Test
    void crcCheckValue() {
        assertEquals(0xCBF43926L, Crc32.of(TestData.bytes("123456789")));
        assertEquals(0L, Crc32.of(new byte[0]));
        assertEquals(0xE8B7BE43L, Crc32.of(TestData.bytes("a")));
    }

    @Test
    void crcMatchesJdkAcrossChunking() {
        Random r = new Random(7);
        for (int round = 0; round < 200; round++) {
            byte[] b = new byte[r.nextInt(400)];
            r.nextBytes(b);
            CRC32 jdk = new CRC32();
            jdk.update(b, 0, b.length);
            Crc32 mine = new Crc32();
            int cut = b.length == 0 ? 0 : r.nextInt(b.length + 1);
            mine.update(b, 0, cut);
            mine.update(b, cut, b.length - cut);
            assertEquals(jdk.getValue(), mine.value());
            assertEquals(jdk.getValue(), Crc32.of(b));
        }
    }

    @Test
    void crcRejectsBadRange() {
        assertThrows(IllegalArgumentException.class, () -> new Crc32().update(new byte[3], 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new Crc32().update(new byte[3], -1, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "a/b", "a/b/", "dir/", "naïve/日本.txt", "a b", "a..b", ".hidden", "a/.b", "..x", "ab:c"})
    void validNames(String n) {
        assertDoesNotThrow(() -> Names.validate(n));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/abs", "a//b", "../x", "a/../b", "a/..", "./a", "a/./b", "..", ".", "a\\b",
        "a\u0000b", "a\nb", "C:x", "c:/x", "a//", "//", "a/\t", "a\u007fb"})
    void invalidNames(String n) {
        assertThrows(IllegalArgumentException.class, () -> Names.validate(n), n);
    }

    @Test
    void rejectionReasonsAreNamed() {
        // "/abs" would also fail the empty-segment rule; the message shows which rule fired first
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Names.validate("/abs")).getMessage().contains("absolute"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Names.validate("a/../b")).getMessage().contains("segment"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Names.validate("")).getMessage().contains("empty"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Names.validate("a\\b")).getMessage().contains("forbidden"));
    }

    @Test
    void loneSurrogateRejected() {
        assertThrows(IllegalArgumentException.class, () -> Names.validate("a\uD800b"));
        assertDoesNotThrow(() -> Names.validate("a\uD83D\uDE00b")); // control: a valid pair
        assertThrows(NullPointerException.class, () -> Names.validate(null));
    }

    @Test
    void entryCopiesAndValidates() {
        byte[] src = {1, 2, 3};
        ArchiveEntry e = new ArchiveEntry("a", src);
        src[0] = 9;
        assertEquals(1, e.data()[0]);
        e.data()[1] = 9;
        assertEquals(2, e.data()[1]);
        assertEquals(e, new ArchiveEntry("a", new byte[] {1, 2, 3}));
        assertEquals(e.hashCode(), new ArchiveEntry("a", new byte[] {1, 2, 3}).hashCode());
        assertNotEquals(e, new ArchiveEntry("a", new byte[] {1, 2, 4}));
        assertNotEquals(e, new ArchiveEntry("b", new byte[] {1, 2, 3}));
        assertFalse(e.directory());
        assertTrue(new ArchiveEntry("d/", new byte[0]).directory());
        assertThrows(IllegalArgumentException.class, () -> new ArchiveEntry("d/", new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new ArchiveEntry("../x", new byte[0]));
    }

    @Test
    void treeChecks() {
        ArchiveEntry a = TestData.file("a", "x");
        assertDoesNotThrow(() -> Archives.checkTree(java.util.List.of(a, TestData.file("b", "y"))));
        assertDoesNotThrow(() -> Archives.checkTree(java.util.List.of(new ArchiveEntry("d/", new byte[0]), TestData.file("d/x", "y"))));
        assertDoesNotThrow(() -> Archives.checkTree(java.util.List.of(TestData.file("d/x", "y")))); // implicit parent
        var dup = assertThrows(ArchiveException.class, () -> Archives.checkTree(java.util.List.of(a, a)));
        assertTrue(dup.getMessage().contains("duplicate"), dup.getMessage());
        var dupDir = assertThrows(ArchiveException.class,
            () -> Archives.checkTree(java.util.List.of(a, new ArchiveEntry("a/", new byte[0]))));
        assertTrue(dupDir.getMessage().contains("duplicate"), dupDir.getMessage());
        var parent = assertThrows(ArchiveException.class,
            () -> Archives.checkTree(java.util.List.of(a, TestData.file("a/b", "y"))));
        assertTrue(parent.getMessage().contains("parent"), parent.getMessage());
        // order must not matter
        assertThrows(ArchiveException.class, () -> Archives.checkTree(java.util.List.of(TestData.file("a/b/c", "y"), a)));
    }
}
