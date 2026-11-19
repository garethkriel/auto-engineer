package ae.build;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {

    private static Builder sample(Store store) {
        Builder b = new Builder(store);
        b.input("a", "héllo ✓");
        b.input("b", "");
        b.rule("x", ctx -> ctx.need("a") + "|" + ctx.need("b"));
        b.rule("y", ctx -> ctx.need("x").toUpperCase(java.util.Locale.ROOT));
        return b;
    }

    @Test
    void roundTripLetsAFreshBuilderReuseEverything(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("build.db");
        Builder first = sample(new Store());
        assertEquals("HÉLLO ✓|", first.build("y"));
        first.store().save(file);

        Builder second = sample(Store.load(file));
        assertEquals("HÉLLO ✓|", second.build("y"));
        assertEquals(List.of(), second.lastExecuted());
        assertEquals(List.of("x", "y"), second.lastReused());

        second.input("b", "!");
        assertEquals("HÉLLO ✓|!", second.build("y"));
        assertEquals(List.of("x", "y"), second.lastExecuted());
    }

    @Test
    void saveOverwritesAndLeavesNoTempFiles(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("build.db");
        Builder b = sample(new Store());
        b.build("y");
        b.store().save(file);
        b.input("a", "other");
        b.build("y");
        b.store().save(file);
        Builder reloaded = sample(Store.load(file));
        reloaded.input("a", "other"); // sample() seeds the original input; restore the saved one
        assertEquals("OTHER|", reloaded.build("y"));
        assertEquals(List.of(), reloaded.lastExecuted(), "saved records match input 'other'");
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(List.of("build.db"), s.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void bytesAreCanonicalRegardlessOfInsertionOrder(@TempDir Path dir) throws IOException {
        Builder one = sample(new Store());
        one.build("y");
        Builder two = sample(new Store());
        two.build("x");
        two.build("y");
        Path f1 = dir.resolve("1.db");
        Path f2 = dir.resolve("2.db");
        one.store().save(f1);
        two.store().save(f2);
        assertArrayEquals(Files.readAllBytes(f1), Files.readAllBytes(f2));
    }

    @Test
    void emptyStoreRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("e.db");
        new Store().save(file);
        assertEquals(0, Store.load(file).size());
    }

    @Test
    void everyTruncationIsRejected(@TempDir Path dir) throws IOException {
        byte[] good = goodBytes(dir);
        for (int len = 0; len < good.length; len++) {
            Path f = dir.resolve("t" + len + ".db");
            Files.write(f, Arrays.copyOf(good, len));
            final int n = len;
            assertThrows(CorruptStoreException.class, () -> Store.load(f), "truncated to " + n);
        }
    }

    @Test
    void everySingleByteFlipIsRejected(@TempDir Path dir) throws IOException {
        byte[] good = goodBytes(dir);
        for (int i = 0; i < good.length; i++) {
            byte[] bad = good.clone();
            bad[i] ^= 0x40;
            Path f = dir.resolve("f" + i + ".db");
            Files.write(f, bad);
            final int at = i;
            assertThrows(CorruptStoreException.class, () -> Store.load(f), "flip at byte " + at);
        }
    }

    @Test
    void validChecksumButBrokenStructureIsRejected(@TempDir Path dir) throws IOException {
        // trailing garbage after a valid body, re-sealed with a correct CRC
        byte[] good = goodBytes(dir);
        byte[] body = Arrays.copyOf(good, good.length - Store.FOOTER_SIZE);
        byte[] extended = Arrays.copyOf(body, body.length + 3);
        Path f = dir.resolve("x.db");
        Files.write(f, Store.seal(extended));
        assertThrows(CorruptStoreException.class, () -> Store.load(f));

        // claim an absurd record count with a valid CRC
        byte[] huge = Arrays.copyOf(body, body.length);
        huge[Store.HEADER_SIZE - 4] = (byte) 0x7f; // high byte of the int count
        Files.write(f, Store.seal(huge));
        assertThrows(CorruptStoreException.class, () -> Store.load(f));
    }

    @Test
    void missingFileIsAnIoErrorNotCorruption(@TempDir Path dir) {
        IOException e = assertThrows(IOException.class, () -> Store.load(dir.resolve("nope.db")));
        assertFalse(e instanceof CorruptStoreException);
    }

    private static byte[] goodBytes(Path dir) throws IOException {
        Builder b = sample(new Store());
        b.build("y");
        Path f = dir.resolve("good.db");
        b.store().save(f);
        return Files.readAllBytes(f);
    }
}
