package ae.tar;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoundTripTest {
    static List<Entry> roundTrip(List<Entry> in) throws Exception {
        return TarReader.read(TarWriter.write(in));
    }

    static String field(byte[] tar, int off, int len) {
        int end = off;
        while (end < off + len && tar[end] != 0) {
            end++;
        }
        return new String(tar, off, end - off, StandardCharsets.UTF_8);
    }

    @Test
    void everyKindRoundTrips() throws Exception {
        List<Entry> in = List.of(
            Entry.dir("d", 0755, 1),
            T.f("d/f", "content"),
            Entry.symlink("d/l", "f"),
            Entry.symlink("up", "../outside"),
            Entry.hardlink("d/h", "d/f"),
            new Entry("full", Entry.Type.FILE, 07777, 65534, 65533, 1700000123L, "", "alice", "staff", new byte[] {0, -1, 1}));
        assertEquals(in, roundTrip(in));
    }

    @Test
    void emptyListRoundTrips() throws Exception {
        assertEquals(List.of(), roundTrip(List.of()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 511, 512, 513, 1024, 1025})
    void sizesAroundBlockBoundaries(int n) throws Exception {
        byte[] data = new byte[n];
        new Random(n).nextBytes(data);
        List<Entry> in = List.of(Entry.file("f", 0644, 0, data));
        byte[] tar = TarWriter.write(in);
        int padded = (n + 511) / 512 * 512; // 0->0, 1->512, 511->512, 512->512, 513->1024
        assertEquals(512 + padded + 1024, tar.length);
        assertEquals(in, TarReader.read(tar));
    }

    private static final String A100 = "a".repeat(100);

    @Test
    void nameFitsHeaderWithoutPaxAtExactlyHundredBytes() throws Exception {
        byte[] tar = TarWriter.write(List.of(T.f(A100, "")));
        assertEquals(0, T.paxCount(tar));
        assertEquals(A100, field(tar, 0, 100));
        assertEquals("", field(tar, 345, 155));
    }

    @Test
    void hundredAndOneBytesWithoutSlashNeedsPax() throws Exception {
        List<Entry> in = List.of(T.f(A100 + "a", ""));
        assertEquals(1, T.paxCount(TarWriter.write(in)));
        assertEquals(in, roundTrip(in));
    }

    @Test
    void splitsIntoPrefixAndNameWhenBothFit() throws Exception {
        String n = "d/" + A100; // 102 bytes
        List<Entry> in = List.of(T.f(n, ""));
        byte[] tar = TarWriter.write(in);
        assertEquals(0, T.paxCount(tar));
        assertEquals("d", field(tar, 345, 155));
        assertEquals(A100, field(tar, 0, 100));
        assertEquals(in, TarReader.read(tar));
    }

    @Test
    void nameOfHundredAndOneAfterTheSplitNeedsPax() throws Exception {
        List<Entry> in = List.of(T.f("d/" + A100 + "a", ""));
        assertEquals(1, T.paxCount(TarWriter.write(in)));
        assertEquals(in, roundTrip(in));
    }

    @Test
    void prefixOfExactly155AndNameOf100FitsTwoFiftySixBytes() throws Exception {
        String n = "p".repeat(155) + "/" + "n".repeat(100);
        List<Entry> in = List.of(T.f(n, ""));
        byte[] tar = TarWriter.write(in);
        assertEquals(256, n.length());
        assertEquals(0, T.paxCount(tar));
        assertEquals("p".repeat(155), field(tar, 345, 155));
        assertEquals(in, TarReader.read(tar));
    }

    @Test
    void prefixOf156NeedsPax() throws Exception {
        List<Entry> in = List.of(T.f("p".repeat(156) + "/n", ""));
        assertEquals(1, T.paxCount(TarWriter.write(in)));
        assertEquals(in, roundTrip(in));
    }

    @Test
    void rightmostFittingSlashIsChosen() throws Exception {
        String n = "x/" + "y".repeat(50) + "/" + "z".repeat(60); // 113 bytes, slashes at 1 and 52
        List<Entry> in = List.of(T.f(n, ""));
        byte[] tar = TarWriter.write(in);
        assertEquals("x/" + "y".repeat(50), field(tar, 345, 155));
        assertEquals("z".repeat(60), field(tar, 0, 100));
        assertEquals(in, TarReader.read(tar));
    }

    @Test
    void shortNameIsNotSplit() throws Exception {
        byte[] tar = TarWriter.write(List.of(T.f("aa/bb/cc", "")));
        assertEquals("aa/bb/cc", field(tar, 0, 100));
        assertEquals("", field(tar, 345, 155));
    }

    @Test
    void directoryWhoseSlashedNameIs101BytesNeedsPaxAndKeepsItsName() throws Exception {
        List<Entry> in = List.of(Entry.dir(A100, 0755, 0)); // stored as 100 a's + "/", only slash is last: cannot split
        byte[] tar = TarWriter.write(in);
        assertEquals(1, T.paxCount(tar));
        assertEquals(in, TarReader.read(tar));
    }

    @Test
    void nonAsciiNamesGoThroughPaxEvenWhenShort() throws Exception {
        List<Entry> in = List.of(T.f("é", "x"), T.f("日本/語", "y"), T.f("😀", "z"));
        byte[] tar = TarWriter.write(in);
        assertEquals(3, T.paxCount(tar));
        assertEquals(in, TarReader.read(tar));
    }

    private static Entry with(long uid, long gid, long mtime, String user, String group, String link) {
        Entry.Type t = link.isEmpty() ? Entry.Type.FILE : Entry.Type.SYMLINK;
        return new Entry("f", t, 0644, uid, gid, mtime, link, user, group, new byte[0]);
    }

    private static void assertPax(int expected, Entry e) throws Exception {
        byte[] tar = TarWriter.write(List.of(e));
        assertEquals(expected, T.paxCount(tar), e.toString());
        assertEquals(List.of(e), TarReader.read(tar));
    }

    @Test
    void numericFieldBoundaries() throws Exception {
        assertPax(0, with(2097151, 2097151, 0, "", "", ""));          // 0o7777777
        assertPax(1, with(2097152, 0, 0, "", "", ""));
        assertPax(1, with(0, 2097152, 0, "", "", ""));
        assertPax(1, with(Long.MAX_VALUE, 0, 0, "", "", ""));
        assertPax(0, with(0, 0, 8589934591L, "", "", ""));            // 0o77777777777
        assertPax(1, with(0, 0, 8589934592L, "", "", ""));
        assertPax(1, with(0, 0, -1, "", "", ""));
        assertPax(1, with(0, 0, Long.MIN_VALUE, "", "", ""));
        assertPax(1, with(0, 0, Long.MAX_VALUE, "", "", ""));
    }

    @Test
    void textFieldBoundaries() throws Exception {
        assertPax(0, with(0, 0, 0, "u".repeat(31), "g".repeat(31), ""));
        assertPax(1, with(0, 0, 0, "u".repeat(32), "", ""));
        assertPax(1, with(0, 0, 0, "", "g".repeat(32), ""));
        assertPax(1, with(0, 0, 0, "ünï", "", ""));
        assertPax(0, with(0, 0, 0, "", "", "t".repeat(100)));
        assertPax(1, with(0, 0, 0, "", "", "t".repeat(101)));
        assertPax(1, with(0, 0, 0, "", "", "../é"));
    }

    @Test
    void manyOverridesShareOnePaxHeader() throws Exception {
        Entry e = new Entry("é/" + "n".repeat(120), Entry.Type.SYMLINK, 0777, Long.MAX_VALUE, 2097152, -5,
            "t".repeat(300), "u".repeat(40), "g".repeat(40), new byte[0]);
        assertPax(1, e);
    }

    @Test
    void seededRandomBatches() throws Exception {
        Random r = new Random(20261126L);
        for (int i = 0; i < 300; i++) {
            List<Entry> in = T.randomEntries(r, 6);
            assertEquals(in, roundTrip(in), "batch " + i);
        }
    }

    @Test
    void archiveLengthIsBlockAligned() {
        Random r = new Random(5);
        for (int i = 0; i < 50; i++) {
            assertEquals(0, TarWriter.write(T.randomEntries(r, 5)).length % 512);
        }
    }
}
