package ae.tar;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeaderLayoutTest {
    @Test
    void plainFileHeaderMatchesHandBuiltBlockByteForByte() {
        Entry e = new Entry("hello.txt", Entry.Type.FILE, 0644, 1000, 1000, 1700000000L, "", "alice", "staff",
            "hello".getBytes(StandardCharsets.UTF_8));
        byte[] tar = TarWriter.write(List.of(e));
        // Literals computed independently with Python: 1000 = 0o1750, 1700000000 = 0o14524770400,
        // unsigned byte sum of the header with the checksum field as spaces = 5074 = 0o11722.
        byte[] want = new byte[512];
        T.put(want, 0, "hello.txt");
        T.put(want, 100, "0000644");
        T.put(want, 108, "0001750");
        T.put(want, 116, "0001750");
        T.put(want, 124, "00000000005");
        T.put(want, 136, "14524770400");
        T.put(want, 148, "011722");
        want[155] = ' ';
        want[156] = '0';
        T.put(want, 257, "ustar");
        T.put(want, 263, "00");
        T.put(want, 265, "alice");
        T.put(want, 297, "staff");
        assertArrayEquals(want, Arrays.copyOfRange(tar, 0, 512));
        byte[] body = new byte[512];
        T.put(body, 0, "hello");
        assertArrayEquals(body, Arrays.copyOfRange(tar, 512, 1024));
        assertEquals(4 * 512, tar.length); // header + data block + two zero blocks
        assertArrayEquals(new byte[1024], Arrays.copyOfRange(tar, 1024, 2048));
    }

    @Test
    void directoryGetsTrailingSlashTypeFiveAndZeroSize() {
        byte[] tar = TarWriter.write(List.of(Entry.dir("d/e", 0755, 7)));
        assertEquals(3 * 512, tar.length);
        assertEquals("d/e/", new String(tar, 0, 4, StandardCharsets.UTF_8));
        assertEquals(0, tar[4]);
        assertEquals('5', tar[156]);
        assertEquals("00000000000", new String(tar, 124, 11, StandardCharsets.US_ASCII));
        assertEquals("0000755", new String(tar, 100, 7, StandardCharsets.US_ASCII));
    }

    @Test
    void symlinkHeaderCarriesLinkNameAndTypeTwo() {
        byte[] tar = TarWriter.write(List.of(Entry.symlink("l", "../t")));
        assertEquals('2', tar[156]);
        assertEquals("../t", new String(tar, 157, 4, StandardCharsets.US_ASCII));
        assertEquals(0, tar[161]);
        assertEquals(3 * 512, tar.length);
    }

    @Test
    void checksumFieldIsSixOctalDigitsNulSpace() {
        byte[] tar = TarWriter.write(List.of(T.f("a", "x")));
        long sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (tar[i] & 0xFF);
        }
        assertEquals(String.format("%06o", sum), new String(tar, 148, 6, StandardCharsets.US_ASCII));
        assertEquals(0, tar[154]);
        assertEquals(' ', tar[155]);
    }

    @Test
    void emptyArchiveIsTwoZeroBlocks() {
        assertArrayEquals(new byte[1024], TarWriter.write(List.of()));
    }

    @Test
    void deviceFieldsAndTrailingPadAreNul() {
        byte[] tar = TarWriter.write(List.of(T.f("a", "x")));
        for (int i = 329; i < 345; i++) {
            assertEquals(0, tar[i], "devmajor/minor byte " + i);
        }
        for (int i = 500; i < 512; i++) {
            assertEquals(0, tar[i], "pad byte " + i);
        }
    }
}
