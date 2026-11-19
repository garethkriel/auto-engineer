package ae.deflate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;
import org.junit.jupiter.api.Test;

class DeflateTest {
    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    private static void assertRoundTrips(byte[] data, Deflate.Level level, String label) throws DataFormatException {
        byte[] packed = Deflate.compress(data, level);
        try {
            JdkOracle.inflate(packed, true);
        } catch (DataFormatException e) {
            throw new AssertionError(label + ": " + e.getMessage() + " packed=" + java.util.HexFormat.of().formatHex(packed, 0, Math.min(8, packed.length)) + " len=" + packed.length, e);
        }
        assertArrayEquals(data, JdkOracle.inflate(packed, true), label + " via JDK Inflater");
        assertArrayEquals(data, Inflate.inflate(packed, 0, Integer.MAX_VALUE).data(), label + " via own Inflate");
    }

    @Test
    void knownVectors() {
        assertArrayEquals(bytes(0x03, 0x00), Deflate.compress(new byte[0], Deflate.Level.DEFAULT));
        assertArrayEquals(bytes(0x4b, 0x04, 0x00), Deflate.compress(Samples.ascii("a"), Deflate.Level.DEFAULT));
        assertArrayEquals(bytes(0xcb, 0x48, 0xcd, 0xc9, 0xc9, 0x07, 0x00),
                Deflate.compress(Samples.ascii("hello"), Deflate.Level.DEFAULT));
    }

    @Test
    void everyLevelRoundTripsTheCorpusThroughBothDecoders() throws DataFormatException {
        for (Map.Entry<String, byte[]> e : Samples.corpus().entrySet()) {
            for (Deflate.Level level : Deflate.Level.values()) {
                assertRoundTrips(e.getValue(), level, e.getKey() + " " + level);
            }
        }
    }

    @Test
    void seededRandomInputsRoundTrip() throws DataFormatException {
        Random r = new Random(2024);
        for (int i = 0; i < 60; i++) {
            int n = r.nextInt(3_000);
            byte[] data = switch (i % 3) {
                case 0 -> Samples.lowEntropy(n, i);
                case 1 -> Samples.text(n, i);
                default -> Samples.random(n, i);
            };
            assertRoundTrips(data, Deflate.Level.values()[i % Deflate.Level.values().length], "seed " + i + " n=" + n);
        }
    }

    @Test
    void matchesAtTheWindowEdgeAreUsedOrSkippedCorrectly() throws DataFormatException {
        for (int gap : new int[] {32_767, 32_768, 32_769, 32_770}) {
            byte[] head = Samples.random(300, gap);
            byte[] data = new byte[gap + 300];
            System.arraycopy(head, 0, data, 0, 300);
            // filler that cannot match the head, then the head repeated exactly `gap` bytes later
            byte[] filler = Samples.text(gap - 300, gap + 1);
            System.arraycopy(filler, 0, data, 300, gap - 300);
            System.arraycopy(head, 0, data, gap, 300);
            assertRoundTrips(data, Deflate.Level.BEST, "gap " + gap);
        }
        // distance exactly 32768 must really be exploited (output much smaller than raw), 32769 must not break.
        byte[] head = Samples.random(300, 5);
        byte[] exact = new byte[32_768 + 300];
        System.arraycopy(head, 0, exact, 0, 300);
        System.arraycopy(Samples.text(32_768 - 300, 6), 0, exact, 300, 32_768 - 300);
        System.arraycopy(head, 0, exact, 32_768, 300);
        byte[] filler = Deflate.compress(java.util.Arrays.copyOf(exact, 32_768), Deflate.Level.BEST);
        byte[] full = Deflate.compress(exact, Deflate.Level.BEST);
        assertTrue(full.length - filler.length < 40, "300-byte repeat at distance 32768 should cost a few bytes, cost "
                + (full.length - filler.length));
    }

    @Test
    void repetitiveDataCompressesHard() {
        byte[] packed = Deflate.compress(new byte[1_000_000], Deflate.Level.DEFAULT);
        assertTrue(packed.length < 8_000, "1 MB of zeros (3877 max-length matches, ~13 bits each) took " + packed.length);
        byte[] text = Samples.text(50_000, 2);
        assertTrue(Deflate.compress(text, Deflate.Level.DEFAULT).length < text.length / 2);
    }

    @Test
    void incompressibleDataFallsBackToStoredBlocks() {
        byte[] data = Samples.random(150_000, 77); // 3 stored blocks: 65535 + 65535 + 18930
        byte[] packed = Deflate.compress(data, Deflate.Level.BEST);
        assertEquals(data.length + 3 * 5, packed.length);
    }

    @Test
    void storedLevelAlwaysEmitsStoredBlocks() throws DataFormatException {
        byte[] data = Samples.text(70_000, 1); // 2 blocks
        byte[] packed = Deflate.compress(data, Deflate.Level.STORED);
        assertEquals(data.length + 2 * 5, packed.length);
        assertArrayEquals(data, JdkOracle.inflate(packed, true));
        // empty input in stored mode is a single empty final stored block
        assertArrayEquals(bytes(0x01, 0x00, 0x00, 0xFF, 0xFF), Deflate.compress(new byte[0], Deflate.Level.STORED));
    }

    @Test
    void deeperSearchNeverProducesLargerOutputOnText() {
        byte[] text = Samples.text(60_000, 8);
        int fast = Deflate.compress(text, Deflate.Level.FAST).length;
        int best = Deflate.compress(text, Deflate.Level.BEST).length;
        assertTrue(best <= fast, "best=" + best + " fast=" + fast);
    }

    @Test
    void compressionIsDeterministic() {
        byte[] data = Samples.text(20_000, 3);
        assertArrayEquals(Deflate.compress(data, Deflate.Level.DEFAULT), Deflate.compress(data, Deflate.Level.DEFAULT));
    }
}
