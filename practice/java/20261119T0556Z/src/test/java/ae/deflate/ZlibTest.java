package ae.deflate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class ZlibTest {
    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    @Test
    void helloHasTheCanonicalHeaderAndTrailer() {
        byte[] z = Zlib.wrap(Samples.ascii("hello"), Deflate.Level.DEFAULT);
        assertArrayEquals(bytes(0x78, 0x9c, 0xcb, 0x48, 0xcd, 0xc9, 0xc9, 0x07, 0x00, 0x06, 0x2c, 0x02, 0x15), z);
    }

    @Test
    void headerCheckBitsAreAlwaysValid() {
        for (Deflate.Level level : Deflate.Level.values()) {
            byte[] z = Zlib.wrap(Samples.ascii("x"), level);
            assertEquals(0x78, z[0] & 0xFF);
            assertEquals(0, (((z[0] & 0xFF) << 8) | (z[1] & 0xFF)) % 31, "level " + level);
        }
    }

    @Test
    void roundTripAndJdkInteropOnTheCorpus() throws DataFormatException {
        for (Map.Entry<String, byte[]> e : Samples.corpus().entrySet()) {
            for (Deflate.Level level : Deflate.Level.values()) {
                byte[] z = Zlib.wrap(e.getValue(), level);
                assertArrayEquals(e.getValue(), Zlib.unwrap(z, Integer.MAX_VALUE), e.getKey() + " own " + level);
                assertArrayEquals(e.getValue(), JdkOracle.inflate(z, false), e.getKey() + " JDK reads ours " + level);
            }
        }
    }

    @Test
    void unwrapsJdkZlibStreams() {
        for (Map.Entry<String, byte[]> e : Samples.corpus().entrySet()) {
            for (int level : new int[] {0, 1, 6, 9}) {
                byte[] z = JdkOracle.deflate(e.getValue(), level, Deflater.DEFAULT_STRATEGY, false);
                assertArrayEquals(e.getValue(), Zlib.unwrap(z, Integer.MAX_VALUE), e.getKey() + " level " + level);
            }
        }
    }

    @Test
    void corruptedChecksumIsRejected() {
        byte[] z = Zlib.wrap(Samples.text(500, 1), Deflate.Level.DEFAULT);
        z[z.length - 1] ^= 1;
        assertThrows(InflateException.class, () -> Zlib.unwrap(z, Integer.MAX_VALUE));
    }

    @Test
    void corruptedPayloadIsRejectedByChecksumOrDecoder() {
        byte[] original = Samples.text(500, 1);
        byte[] z = Zlib.wrap(original, Deflate.Level.DEFAULT);
        for (int i = 2; i < z.length - 4; i++) {
            byte[] bad = z.clone();
            bad[i] ^= 0x10;
            try {
                byte[] out = Zlib.unwrap(bad, 1 << 20);
                // a flip may legally decode to the same bytes (e.g. unused bits), never to different ones
                assertArrayEquals(original, out, "flip at " + i + " produced different data without an error");
            } catch (InflateException expected) {
                // fine
            }
        }
    }

    @Test
    void headerProblemsAreRejected() {
        byte[] ok = Zlib.wrap(Samples.ascii("a"), Deflate.Level.DEFAULT);
        byte[] badMethod = ok.clone();
        badMethod[0] = 0x79; // CM=9
        assertThrows(InflateException.class, () -> Zlib.unwrap(badMethod, 100));
        byte[] badCheck = ok.clone();
        badCheck[1] ^= 1;
        assertThrows(InflateException.class, () -> Zlib.unwrap(badCheck, 100));
        byte[] bigWindow = ok.clone();
        bigWindow[0] = (byte) 0x88; // CINFO=8 (invalid), CM=8
        bigWindow[1] = (byte) (31 - (0x8800 % 31));
        assertThrows(InflateException.class, () -> Zlib.unwrap(bigWindow, 100));
        byte[] dict = ok.clone();
        dict[1] = (byte) (0x20 | 0); // FDICT set; fix FCHECK
        dict[1] = (byte) (0x20 + (31 - ((0x7800 + 0x20) % 31)) % 31);
        assertThrows(InflateException.class, () -> Zlib.unwrap(dict, 100));
        assertThrows(InflateException.class, () -> Zlib.unwrap(new byte[] {0x78}, 100));
    }

    @Test
    void trailingBytesAndMissingTrailerAreRejected() {
        byte[] ok = Zlib.wrap(Samples.ascii("abc"), Deflate.Level.DEFAULT);
        byte[] extra = java.util.Arrays.copyOf(ok, ok.length + 1);
        assertThrows(InflateException.class, () -> Zlib.unwrap(extra, 100));
        byte[] shortTrailer = java.util.Arrays.copyOf(ok, ok.length - 1);
        assertThrows(InflateException.class, () -> Zlib.unwrap(shortTrailer, 100));
    }
}
