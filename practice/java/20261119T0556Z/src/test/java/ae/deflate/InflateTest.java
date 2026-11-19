package ae.deflate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class InflateTest {
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    private static byte[] inflate(byte[] in) {
        return Inflate.inflate(in, 0, NO_LIMIT).data();
    }

    @Test
    void storedBlock() {
        byte[] in = bytes(0x01, 0x05, 0x00, 0xFA, 0xFF, 'h', 'e', 'l', 'l', 'o');
        assertArrayEquals(Samples.ascii("hello"), inflate(in));
    }

    @Test
    void fixedBlockVectors() {
        assertArrayEquals(new byte[0], inflate(bytes(0x03, 0x00)));
        assertArrayEquals(Samples.ascii("a"), inflate(bytes(0x4b, 0x04, 0x00)));
        assertArrayEquals(Samples.ascii("hello"), inflate(bytes(0xcb, 0x48, 0xcd, 0xc9, 0xc9, 0x07, 0x00)));
    }

    @Test
    void resultReportsConsumedBytesAndIgnoresTrailingData() {
        byte[] in = bytes(0x4b, 0x04, 0x00, 0xDE, 0xAD);
        Inflate.Result r = Inflate.inflate(in, 0, NO_LIMIT);
        assertEquals(3, r.consumed());
        assertArrayEquals(Samples.ascii("a"), r.data());
    }

    @Test
    void offsetIsHonoured() {
        byte[] in = bytes(9, 9, 0x4b, 0x04, 0x00);
        Inflate.Result r = Inflate.inflate(in, 2, NO_LIMIT);
        assertEquals(3, r.consumed());
        assertArrayEquals(Samples.ascii("a"), r.data());
    }

    @Test
    void decodesEveryJdkLevelAndStrategyOnTheCorpus() {
        int[] strategies = {Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY};
        int checked = 0;
        for (Map.Entry<String, byte[]> e : Samples.corpus().entrySet()) {
            for (int level = 0; level <= 9; level++) {
                for (int strategy : strategies) {
                    byte[] packed = JdkOracle.deflate(e.getValue(), level, strategy, true);
                    String label = e.getKey() + " level=" + level + " strategy=" + strategy;
                    assertArrayEquals(e.getValue(), inflate(packed), label);
                    checked++;
                }
            }
        }
        assertEquals(10 * 10 * 3, checked);
    }

    @Test
    void multipleBlocksFromFlushAreDecoded() {
        // Deflater with SYNC_FLUSH after each chunk emits several blocks (empty stored sync markers included).
        Deflater d = new Deflater(6, true);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            String chunk = "chunk-" + i + "-" + "xyz".repeat(100 * (i + 1));
            expected.append(chunk);
            d.setInput(Samples.ascii(chunk));
            out.write(buf, 0, d.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH));
        }
        d.finish();
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        assertArrayEquals(Samples.ascii(expected.toString()), inflate(out.toByteArray()));
    }

    @Test
    void reservedBlockTypeIsRejected() {
        assertThrows(InflateException.class, () -> inflate(bytes(0x07, 0, 0, 0)));
    }

    @Test
    void storedLengthComplementMismatchIsRejected() {
        assertThrows(InflateException.class, () -> inflate(bytes(0x01, 0x05, 0x00, 0xFA, 0xFE, 'h', 'e', 'l', 'l', 'o')));
    }

    @Test
    void storedBlockShorterThanDeclaredIsRejected() {
        assertThrows(InflateException.class, () -> inflate(bytes(0x01, 0x05, 0x00, 0xFA, 0xFF, 'h', 'e')));
    }

    @Test
    void distanceBeyondProducedOutputIsRejected() {
        // fixed block: length-3 match (symbol 257) at distance 1 with no output yet.
        BitWriter w = new BitWriter();
        w.bits(1, 1);
        w.bits(1, 2);
        w.code(0b0000001, 7); // symbol 257
        w.code(0, 5); // distance symbol 0 -> distance 1
        w.code(0, 7); // end of block
        assertThrows(InflateException.class, () -> inflate(w.toByteArray()));
    }

    @Test
    void invalidFixedSymbolsAreRejected() {
        // fixed literal/length symbol 286 (8-bit code 11000110) is reserved.
        BitWriter w = new BitWriter();
        w.bits(1, 1);
        w.bits(1, 2);
        w.code(0xC0 + (286 - 280), 8);
        assertThrows(InflateException.class, () -> inflate(w.toByteArray()));
        // distance symbol 30 (5-bit code 11110) is reserved.
        BitWriter w2 = new BitWriter();
        w2.bits(1, 1);
        w2.bits(1, 2);
        w2.code(0x30 + 'x', 8);
        w2.code(0b0000001, 7);
        w2.code(30, 5);
        assertThrows(InflateException.class, () -> inflate(w2.toByteArray()));
    }

    @Test
    void outputLimitStopsADecompressionBomb() {
        byte[] bomb = JdkOracle.deflate(new byte[5_000_000], 9, Deflater.DEFAULT_STRATEGY, true);
        assertEquals(true, bomb.length < 6_000, "fixture should be tiny but was " + bomb.length);
        assertThrows(InflateException.class, () -> Inflate.inflate(bomb, 0, 1_000));
        assertEquals(5_000_000, Inflate.inflate(bomb, 0, 5_000_000).data().length);
        assertThrows(InflateException.class, () -> Inflate.inflate(bomb, 0, 4_999_999));
    }

    @Test
    void everyTruncationOfAValidStreamFails() {
        for (String name : new String[] {"text 50000", "low entropy 40000", "hello"}) {
            byte[] packed = JdkOracle.deflate(Samples.corpus().get(name), 6, Deflater.DEFAULT_STRATEGY, true);
            int step = Math.max(1, packed.length / 400);
            for (int cut = 0; cut < packed.length; cut += step) {
                byte[] shorter = java.util.Arrays.copyOf(packed, cut);
                assertThrows(InflateException.class, () -> inflate(shorter), name + " cut at " + cut);
            }
            assertThrows(InflateException.class, () -> inflate(java.util.Arrays.copyOf(packed, packed.length - 1)), name);
        }
    }

    @Test
    void everySingleByteFlipYieldsDataOrInflateExceptionOnly() {
        byte[] original = Samples.text(3_000, 11);
        for (int level : new int[] {1, 6, 9}) {
            byte[] packed = JdkOracle.deflate(original, level, Deflater.DEFAULT_STRATEGY, true);
            for (int i = 0; i < packed.length; i++) {
                for (int mask : new int[] {0x01, 0x80, 0xFF}) {
                    byte[] bad = packed.clone();
                    bad[i] ^= (byte) mask;
                    try {
                        Inflate.inflate(bad, 0, 1 << 22);
                    } catch (InflateException expected) {
                        // acceptable
                    }
                }
            }
        }
    }

    @Test
    void dynamicBlockWithBadCodeLengthRepeatIsRejected() {
        // dynamic header whose first code-length symbol is 16 (repeat previous) with nothing before it.
        BitWriter w = new BitWriter();
        w.bits(1, 1);
        w.bits(2, 2); // dynamic
        w.bits(0, 5); // HLIT 257
        w.bits(0, 5); // HDIST 1
        w.bits(15, 4); // HCLEN 19
        // code-length code: only symbols 16 and 0 get length 1 -> complete 2-symbol code.
        int[] order = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
        for (int sym : order) {
            w.bits(sym == 16 || sym == 0 ? 1 : 0, 3);
        }
        // canonical: symbol 0 -> code 0, symbol 16 -> code 1. Emit 16 first.
        w.code(1, 1);
        w.bits(0, 2);
        assertThrows(InflateException.class, () -> inflate(w.toByteArray()));
    }
}
