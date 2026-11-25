package ae.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Sha256Test {
    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    @Test
    void emptyInput() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hex.s(Sha256.hash(new byte[0])));
    }

    @Test
    void abc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hex.s(Sha256.hash(ascii("abc"))));
    }

    @Test
    void twoBlockVector() {
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Hex.s(Sha256.hash(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))));
    }

    @Test
    void millionA() {
        Sha256 h = new Sha256();
        byte[] chunk = new byte[1000];
        java.util.Arrays.fill(chunk, (byte) 'a');
        for (int i = 0; i < 1000; i++) h.update(chunk);
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Hex.s(h.digest()));
    }

    @Test
    void paddingEdgeLengthsMatchJdk() throws Exception {
        Random r = new Random(1);
        for (int len = 0; len <= 200; len++) {
            byte[] d = new byte[len];
            r.nextBytes(d);
            assertEquals(Hex.s(MessageDigest.getInstance("SHA-256").digest(d)), Hex.s(Sha256.hash(d)), "len " + len);
        }
    }

    @Test
    void streamingSplitAtEveryOffsetEqualsOneShot() {
        byte[] d = new byte[150];
        new Random(2).nextBytes(d);
        byte[] expected = Sha256.hash(d);
        for (int cut = 0; cut <= d.length; cut++) {
            Sha256 h = new Sha256();
            h.update(d, 0, cut);
            h.update(d, cut, d.length - cut);
            assertArrayEquals(expected, h.digest(), "cut " + cut);
        }
    }

    @Test
    void byteAtATimeEqualsOneShot() {
        byte[] d = new byte[130];
        new Random(3).nextBytes(d);
        Sha256 h = new Sha256();
        for (byte x : d) h.update(new byte[] {x});
        assertArrayEquals(Sha256.hash(d), h.digest());
    }

    @Test
    void digestResetsTheInstance() {
        Sha256 h = new Sha256();
        h.update(ascii("garbage"));
        h.digest();
        h.update(ascii("abc"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hex.s(h.digest()));
    }

    @Test
    void copyIsIndependent() {
        Sha256 a = new Sha256();
        a.update(ascii("ab"));
        Sha256 b = a.copy();
        a.update(ascii("c"));
        b.update(ascii("x"));
        assertEquals(Hex.s(Sha256.hash(ascii("abc"))), Hex.s(a.digest()));
        assertEquals(Hex.s(Sha256.hash(ascii("abx"))), Hex.s(b.digest()));
    }

    @Test
    void copyMidBlockAndAfterBlock() {
        byte[] d = new byte[70];
        new Random(4).nextBytes(d);
        Sha256 a = new Sha256();
        a.update(d, 0, 64);
        Sha256 c = a.copy();
        a.update(d, 64, 6);
        c.update(d, 64, 6);
        assertArrayEquals(a.digest(), c.digest());
    }

    @Test
    void badRangesAndNulls() {
        Sha256 h = new Sha256();
        assertThrows(NullPointerException.class, () -> h.update(null));
        assertThrows(IndexOutOfBoundsException.class, () -> h.update(new byte[4], 3, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> h.update(new byte[4], -1, 2));
        h.update(new byte[4], 4, 0);
        assertEquals(Hex.s(Sha256.hash(new byte[0])), Hex.s(h.digest()));
    }

    @Test
    void inputIsNotMutated() {
        byte[] d = {1, 2, 3};
        Sha256.hash(d);
        assertArrayEquals(new byte[] {1, 2, 3}, d);
    }
}
