package ae.torrent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TorrentTest {
    static byte[] data(int n, long seed) {
        byte[] d = new byte[n];
        new Random(seed).nextBytes(d);
        return d;
    }

    static byte[] sha1(byte[] x) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(x);
    }

    @Test
    void sha1PublishedVector() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                HexFormat.of().formatHex(Torrent.sha1("abc".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void pieceLayoutWithShortLastPiece() {
        // 10 bytes in pieces of 4: sizes 4,4,2  (ceil(10/4) = 3)
        Torrent t = Torrent.create("f.bin", data(10, 1), 4);
        assertEquals(3, t.pieceCount());
        assertEquals(4, t.pieceSize(0));
        assertEquals(4, t.pieceSize(1));
        assertEquals(2, t.pieceSize(2));
        assertEquals(10, t.length());
        assertThrows(IllegalArgumentException.class, () -> t.pieceSize(3));
        assertThrows(IllegalArgumentException.class, () -> t.pieceSize(-1));
    }

    @Test
    void exactMultipleHasNoExtraPiece() {
        assertEquals(2, Torrent.create("f", data(8, 2), 4).pieceCount()); // 8/4
        assertEquals(1, Torrent.create("f", data(1, 2), 4).pieceCount());
        assertEquals(0, Torrent.create("f", new byte[0], 4).pieceCount());
    }

    @Test
    void verifyPieceAcceptsOnlyTheRightBytes() {
        byte[] d = data(10, 3);
        Torrent t = Torrent.create("f", d, 4);
        assertTrue(t.verifyPiece(0, Arrays.copyOfRange(d, 0, 4)));
        assertTrue(t.verifyPiece(2, Arrays.copyOfRange(d, 8, 10)));
        byte[] bad = Arrays.copyOfRange(d, 0, 4);
        bad[3] ^= 1;
        assertFalse(t.verifyPiece(0, bad));
        assertFalse(t.verifyPiece(0, Arrays.copyOfRange(d, 0, 3)), "wrong length");
        assertFalse(t.verifyPiece(1, Arrays.copyOfRange(d, 0, 4)), "right size, other piece");
    }

    @Test
    void roundTripThroughBytes() {
        Torrent t = Torrent.create("movie.mkv", data(1000, 4), 64);
        Torrent back = Torrent.parse(t.toBytes());
        assertEquals(t, back);
        assertEquals("movie.mkv", back.name());
        assertEquals(16, back.pieceCount()); // ceil(1000/64) = 15.6 -> 16
        assertEquals(1000 - 15 * 64, back.pieceSize(15)); // 40
        assertArrayEquals(t.infoHash(), back.infoHash());
    }

    @Test
    void infoHashIsSha1OfEncodedInfoDict() throws Exception {
        byte[] d = data(100, 5);
        Torrent t = Torrent.create("n", d, 32);
        BDict outer = (BDict) Bencode.decode(t.toBytes());
        BDict info = (BDict) outer.get("info");
        assertArrayEquals(sha1(Bencode.encode(info)), t.infoHash());
        assertEquals(20, t.infoHash().length);
    }

    @Test
    void extraInfoKeysArePreservedAndChangeTheHash() {
        Torrent t = Torrent.create("n", data(100, 6), 32);
        BDict outer = (BDict) Bencode.decode(t.toBytes());
        BDict info = (BDict) outer.get("info");
        BDict.Builder ib = BDict.builder();
        for (var e : info.entries()) {
            ib.put(e.getKey(), e.getValue());
        }
        ib.put("private", new BInt(1));
        byte[] withExtra = Bencode.encode(BDict.builder().put("info", ib.build()).build());
        Torrent t2 = Torrent.parse(withExtra);
        assertFalse(Arrays.equals(t.infoHash(), t2.infoHash()));
        assertArrayEquals(withExtra, t2.toBytes(), "extra key survives re-encoding");
        assertEquals(t.pieceCount(), t2.pieceCount());
    }

    @Test
    void differentContentOrNameOrPieceLengthChangeInfoHash() {
        byte[] d = data(100, 7);
        byte[] d2 = d.clone();
        d2[50] ^= 1;
        Torrent a = Torrent.create("n", d, 32);
        assertFalse(Arrays.equals(a.infoHash(), Torrent.create("n", d2, 32).infoHash()));
        assertFalse(Arrays.equals(a.infoHash(), Torrent.create("m", d, 32).infoHash()));
        assertFalse(Arrays.equals(a.infoHash(), Torrent.create("n", d, 16).infoHash()));
        assertArrayEquals(a.infoHash(), Torrent.create("n", d.clone(), 32).infoHash());
    }

    private static BDict info(String name, long pieceLength, long length, byte[] pieces) {
        return BDict.builder()
                .put("name", BStr.of(name))
                .put("piece length", new BInt(pieceLength))
                .put("length", new BInt(length))
                .put("pieces", new BStr(pieces))
                .build();
    }

    private static byte[] wrap(BDict info) {
        return Bencode.encode(BDict.builder().put("info", info).build());
    }

    @Test
    void metainfoValidationRulesEachRejectOnTheirOwn() {
        byte[] p2 = new byte[40]; // two hashes
        // control: 8 bytes in pieces of 4 -> 2 pieces
        assertDoesNotThrow(() -> Torrent.parse(wrap(info("n", 4, 8, p2))));

        assertThrows(BencodeException.class, () -> Torrent.parse(wrap(info("", 4, 8, p2))), "empty name");
        assertThrows(BencodeException.class, () -> Torrent.parse(wrap(info("n", 0, 8, p2))), "zero piece length");
        assertThrows(BencodeException.class, () -> Torrent.parse(wrap(info("n", -4, 8, p2))), "negative piece length");
        assertThrows(BencodeException.class, () -> Torrent.parse(wrap(info("n", 4, -8, p2))), "negative length");
        assertThrows(BencodeException.class, () -> Torrent.parse(wrap(info("n", 4, 8, new byte[39]))), "hash bytes not multiple of 20");
        assertThrows(BencodeException.class, () -> Torrent.parse(wrap(info("n", 4, 9, p2))), "too few hashes (needs 3)");
        assertThrows(BencodeException.class, () -> Torrent.parse(wrap(info("n", 4, 4, p2))), "too many hashes (needs 1)");
        assertThrows(BencodeException.class, () -> Torrent.parse(wrap(info("n", 4, 0, p2))), "zero length needs zero hashes");
        assertDoesNotThrow(() -> Torrent.parse(wrap(info("n", 4, 0, new byte[0]))));
    }

    @Test
    void metainfoStructureErrors() {
        byte[] p = new byte[20];
        assertThrows(BencodeException.class, () -> Torrent.parse(Bencode.encode(new BInt(1))), "not a dict");
        assertThrows(BencodeException.class, () -> Torrent.parse(Bencode.encode(BDict.builder().build())), "no info");
        assertThrows(BencodeException.class, () -> Torrent.parse(
                Bencode.encode(BDict.builder().put("info", new BInt(3)).build())), "info wrong type");
        for (String missing : new String[] {"name", "piece length", "length", "pieces"}) {
            BDict full = info("n", 4, 4, p);
            BDict.Builder b = BDict.builder();
            for (var e : full.entries()) {
                if (!e.getKey().utf8().equals(missing)) {
                    b.put(e.getKey(), e.getValue());
                }
            }
            assertThrows(BencodeException.class, () -> Torrent.parse(wrap(b.build())), "missing " + missing);
        }
        BDict wrongType = BDict.builder()
                .put("name", new BInt(5)).put("piece length", new BInt(4))
                .put("length", new BInt(4)).put("pieces", new BStr(p)).build();
        assertThrows(BencodeException.class, () -> Torrent.parse(wrap(wrongType)), "name not a string");
    }

    @Test
    void createRejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> Torrent.create("n", data(4, 1), 0));
        assertThrows(IllegalArgumentException.class, () -> Torrent.create("", data(4, 1), 4));
        assertThrows(NullPointerException.class, () -> Torrent.create("n", null, 4));
    }

    @Test
    void randomTorrentsRoundTrip() {
        Random r = new Random(99);
        for (int i = 0; i < 200; i++) {
            byte[] d = data(r.nextInt(300), r.nextLong());
            int pl = 1 + r.nextInt(70);
            Torrent t = Torrent.create("t" + i, d, pl);
            Torrent back = Torrent.parse(t.toBytes());
            assertEquals(t, back);
            long total = 0;
            for (int p = 0; p < back.pieceCount(); p++) {
                total += back.pieceSize(p);
                int from = p * pl;
                assertTrue(back.verifyPiece(p, Arrays.copyOfRange(d, from, from + back.pieceSize(p))));
            }
            assertEquals(d.length, total);
        }
    }
}
