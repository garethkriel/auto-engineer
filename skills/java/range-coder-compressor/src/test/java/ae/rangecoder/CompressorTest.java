package ae.rangecoder;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class CompressorTest {

    private static final int HEADER = Compressor.HEADER_SIZE; // 'R','C', engine, length(4), crc(4)

    private static byte[] random(long seed, int n) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static byte[] text(int repeat) {
        String s = "the quick brown fox jumps over the lazy dog. pack my box with five dozen liquor jugs. ";
        return s.repeat(repeat).getBytes(StandardCharsets.US_ASCII);
    }

    /** Empirical order-0 entropy in bits for the whole sample, computed independently of the codec. */
    private static double entropyBits(byte[] data) {
        int[] c = new int[256];
        for (byte b : data) c[b & 0xFF]++;
        double h = 0;
        for (int x : c) if (x > 0) h -= x * (Math.log((double) x / data.length) / Math.log(2));
        return h;
    }

    @Test
    void roundTripEveryEngineOnEdgeInputs() {
        byte[] allValues = new byte[256];
        for (int i = 0; i < 256; i++) allValues[i] = (byte) i;
        byte[][] inputs = {
            new byte[0], {0}, {(byte) 0xFF}, allValues, random(1, 10_000), text(40), new byte[3000],
        };
        for (Compressor.Engine eng : Compressor.Engine.values()) {
            for (int k = 0; k < inputs.length; k++) {
                byte[] c = Compressor.compress(inputs[k], eng);
                assertArrayEquals(inputs[k], Compressor.decompress(c), eng + " input " + k);
            }
        }
    }

    @Test
    void headerLayoutIsExactlyAsDocumented() {
        byte[] in = "hello".getBytes(StandardCharsets.US_ASCII);
        byte[] c = Compressor.compress(in, Compressor.Engine.FREQ1);
        assertEquals('R', c[0]);
        assertEquals('C', c[1]);
        assertEquals(Compressor.Engine.FREQ1.ordinal(), c[2]);
        assertEquals(0, c[3]); assertEquals(0, c[4]); assertEquals(0, c[5]); assertEquals(5, c[6]);
        CRC32 crc = new CRC32();
        crc.update(in);
        long v = crc.getValue();
        assertEquals((byte) (v >>> 24), c[7]);
        assertEquals((byte) (v >>> 16), c[8]);
        assertEquals((byte) (v >>> 8), c[9]);
        assertEquals((byte) v, c[10]);
        assertEquals(11, HEADER);
        assertTrue(c.length >= HEADER + 5);
    }

    @Test
    void emptyInputIsHeaderPlusFiveByteFlush() {
        for (Compressor.Engine e : Compressor.Engine.values()) {
            assertEquals(HEADER + 5, Compressor.compress(new byte[0], e).length, e.toString());
        }
    }

    @Test
    void millionEqualBytesCompressToAlmostNothing() {
        byte[] zeros = new byte[1_000_000];
        for (Compressor.Engine e : Compressor.Engine.values()) {
            byte[] c = Compressor.compress(zeros, e);
            // BitModel saturates at p = 1 - 31/2048 (~0.022 bits per bit), so the bit engines cost ~22 KB here
            int limit = e == Compressor.Engine.BIT0 || e == Compressor.Engine.BIT1 ? 24_000 : 3000;
            assertTrue(c.length < limit, e + " size " + c.length);
            assertArrayEquals(zeros, Compressor.decompress(c), e.toString());
        }
    }

    @Test
    void iidSourceIsCodedNearItsEmpiricalEntropy() {
        // probabilities 1/2, 1/4, 1/8, 1/8 over symbols 10..13 -> 1.75 bits per symbol
        Random r = new Random(31);
        byte[] data = new byte[40_000];
        for (int i = 0; i < data.length; i++) {
            int x = r.nextInt(8);
            data[i] = (byte) (x < 4 ? 10 : x < 6 ? 11 : x == 6 ? 12 : 13);
        }
        double hBytes = entropyBits(data) / 8;
        assertTrue(hBytes > 8500 && hBytes < 9000, "fixture entropy " + hBytes);
        // the 1/32 decay of BitModel costs ~9% on this source (measured 9557 vs 8755); the count model is within 3%
        double[] slack = {1.03, 1.12};
        Compressor.Engine[] engines = {Compressor.Engine.FREQ0, Compressor.Engine.BIT0};
        for (int k = 0; k < engines.length; k++) {
            int size = Compressor.compress(data, engines[k]).length - HEADER;
            assertTrue(size >= hBytes * 0.97, engines[k] + " beats entropy suspiciously: " + size + " vs " + hBytes);
            assertTrue(size <= hBytes * slack[k] + 40, engines[k] + " too large: " + size + " vs " + hBytes);
        }
    }

    @Test
    void uniformRandomBytesAreNotInflatedMuch() {
        byte[] data = random(2, 50_000);
        for (Compressor.Engine e : new Compressor.Engine[] {Compressor.Engine.FREQ0, Compressor.Engine.BIT0}) {
            int size = Compressor.compress(data, e).length;
            assertTrue(size <= data.length * 1.03 + HEADER, e + " size " + size);
        }
    }

    @Test
    void higherOrderContextsBeatOrderZeroOnMarkovData() {
        // Fixture: each byte is the successor in a cycle "abcdefgh" with probability 0.9, else a random letter.
        Random r = new Random(4);
        byte[] data = new byte[30_000];
        int prev = 0;
        for (int i = 0; i < data.length; i++) {
            int cur = r.nextInt(10) == 0 ? r.nextInt(8) : (prev + 1) % 8;
            data[i] = (byte) ('a' + cur);
            prev = cur;
        }
        int f0 = Compressor.compress(data, Compressor.Engine.FREQ0).length;
        int f1 = Compressor.compress(data, Compressor.Engine.FREQ1).length;
        int b0 = Compressor.compress(data, Compressor.Engine.BIT0).length;
        int b1 = Compressor.compress(data, Compressor.Engine.BIT1).length;
        // order 0 sees ~3 bits/symbol = 11250 bytes; order 1 sees ~0.9 bits/symbol
        assertTrue(f0 > 10_000, "f0 " + f0);
        assertTrue(f1 < f0 / 2, "f1 " + f1 + " f0 " + f0);
        assertTrue(b1 < b0 / 2, "b1 " + b1 + " b0 " + b0);
        // the two order-1 engines model the same thing; the decaying bit model pays an adaptation tax (measured 2641 vs 3237)
        assertTrue(b1 >= f1 * 0.9 && b1 <= f1 * 1.35, "f1 " + f1 + " b1 " + b1);
    }

    @Test
    void orderTwoHelpsWhenTwoBytesDetermineTheNext() {
        // Fixture: x[i] = x[i-1] XOR x[i-2]-ish pattern over a 4 letter alphabet is deterministic given the last two.
        byte[] data = new byte[20_000];
        data[0] = 0; data[1] = 1;
        Random r = new Random(6);
        for (int i = 2; i < data.length; i++) {
            data[i] = (byte) ((data[i - 1] + data[i - 2] + (r.nextInt(50) == 0 ? 1 : 0)) % 5);
        }
        int f1 = Compressor.compress(data, Compressor.Engine.FREQ1).length;
        int f2 = Compressor.compress(data, Compressor.Engine.FREQ2).length;
        assertTrue(f2 < f1 * 0.6, "f1 " + f1 + " f2 " + f2);
    }

    @Test
    void compressBestPicksTheSmallestEngine() {
        byte[][] inputs = {text(60), random(8, 3000), new byte[5000], new byte[0]};
        for (byte[] in : inputs) {
            byte[] best = Compressor.compressBest(in);
            int min = Integer.MAX_VALUE;
            for (Compressor.Engine e : Compressor.Engine.values()) {
                min = Math.min(min, Compressor.compress(in, e).length);
            }
            assertEquals(min, best.length);
            assertArrayEquals(in, Compressor.decompress(best));
        }
    }

    @Test
    void compressBestBreaksTiesTowardLowestOrdinal() {
        byte[] best = Compressor.compressBest(new byte[0]);
        assertEquals(0, best[2]);
    }

    @Test
    void compressionIsDeterministicAndDoesNotMutateInput() {
        byte[] in = text(10);
        byte[] copy = in.clone();
        for (Compressor.Engine e : Compressor.Engine.values()) {
            assertArrayEquals(Compressor.compress(in, e), Compressor.compress(in, e), e.toString());
        }
        assertArrayEquals(copy, in);
    }

    @Test
    void textCompressesWellWithContextModels() {
        byte[] in = text(60);
        assertTrue(Compressor.compress(in, Compressor.Engine.FREQ2).length < in.length / 8);
    }

    @Test
    void malformedContainersAreRejectedWithCodecException() {
        byte[] good = Compressor.compress(text(3), Compressor.Engine.FREQ0);

        byte[] badMagic = good.clone(); badMagic[0] = 'X';
        assertEquals("bad magic", assertThrows(CodecException.class, () -> Compressor.decompress(badMagic)).getMessage());

        byte[] badMagic2 = good.clone(); badMagic2[1] = 'X';
        assertEquals("bad magic", assertThrows(CodecException.class, () -> Compressor.decompress(badMagic2)).getMessage());

        byte[] badEngine = good.clone(); badEngine[2] = (byte) Compressor.Engine.values().length;
        assertEquals("unknown engine", assertThrows(CodecException.class, () -> Compressor.decompress(badEngine)).getMessage());

        byte[] negEngine = good.clone(); negEngine[2] = (byte) 0x80;
        assertEquals("unknown engine", assertThrows(CodecException.class, () -> Compressor.decompress(negEngine)).getMessage());

        assertEquals("truncated header", assertThrows(CodecException.class,
                () -> Compressor.decompress(Arrays.copyOf(good, HEADER - 1))).getMessage());

        byte[] hugeLen = good.clone(); hugeLen[3] = 0x7F;
        assertEquals("length too large", assertThrows(CodecException.class, () -> Compressor.decompress(hugeLen)).getMessage());

        byte[] negLen = good.clone(); negLen[3] = (byte) 0x80;
        assertEquals("length too large", assertThrows(CodecException.class, () -> Compressor.decompress(negLen)).getMessage());

        byte[] badCrc = good.clone(); badCrc[HEADER - 1] ^= 1;
        assertEquals("crc mismatch", assertThrows(CodecException.class, () -> Compressor.decompress(badCrc)).getMessage());
    }

    @Test
    void controlInputWithOnlyTheCrcDifferenceStillLoadsWhenCrcIsFixed() {
        // control for the rejection tests above: the untouched container is accepted
        byte[] in = text(3);
        assertArrayEquals(in, Compressor.decompress(Compressor.compress(in, Compressor.Engine.FREQ0)));
    }

    @Test
    void everySingleByteFlipEitherThrowsOrReturnsTheOriginal() {
        byte[] in = text(2);
        for (Compressor.Engine e : Compressor.Engine.values()) {
            byte[] good = Compressor.compress(in, e);
            int thrown = 0;
            for (int i = 0; i < good.length; i++) {
                byte[] bad = good.clone();
                bad[i] ^= 0x5A;
                try {
                    assertArrayEquals(in, Compressor.decompress(bad), e + " flip " + i);
                } catch (CodecException ex) {
                    thrown++;
                }
            }
            assertTrue(thrown > good.length * 9 / 10, e + " only " + thrown + " of " + good.length + " flips detected");
        }
    }

    @Test
    void everyTruncationThrows() {
        byte[] in = text(2);
        for (Compressor.Engine e : Compressor.Engine.values()) {
            byte[] good = Compressor.compress(in, e);
            for (int len = 0; len < good.length; len++) {
                byte[] cut = Arrays.copyOf(good, len);
                assertThrows(CodecException.class, () -> Compressor.decompress(cut), e + " len " + len);
            }
        }
    }

    @Test
    void randomizedDifferentialAcrossEngines() {
        Random r = new Random(12345);
        for (int round = 0; round < 60; round++) {
            int n = r.nextInt(2000);
            int alphabet = 1 + r.nextInt(256);
            byte[] in = new byte[n];
            for (int i = 0; i < n; i++) in[i] = (byte) (r.nextInt(5) == 0 && i > 0 ? in[i - 1] : r.nextInt(alphabet));
            for (Compressor.Engine e : Compressor.Engine.values()) {
                assertArrayEquals(in, Compressor.decompress(Compressor.compress(in, e)), "round " + round + " " + e);
            }
        }
    }

    @Test
    void nullInputRejected() {
        assertThrows(NullPointerException.class, () -> Compressor.compress(null, Compressor.Engine.FREQ0));
        assertThrows(NullPointerException.class, () -> Compressor.compress(new byte[1], null));
        assertThrows(NullPointerException.class, () -> Compressor.decompress(null));
    }

    @Test
    void contextCapKeepsOrderTwoBoundedAndRoundTrips() {
        // 300 KB of random bytes touch far more than MAX_CONTEXTS distinct byte pairs, so the shared fallback is used
        byte[] in = random(99, 300_000);
        byte[] c = Compressor.compress(in, Compressor.Engine.FREQ2);
        assertArrayEquals(in, Compressor.decompress(c));
    }

    @Test
    void lengthBeyondTheRatioBoundIsRejectedBeforeDecoding() {
        byte[] good = Compressor.compress(text(1), Compressor.Engine.FREQ0);
        int coderBytes = good.length - HEADER;
        byte[] lying = good.clone();
        int claimed = (int) Math.min(1 << 28, (long) Compressor.MAX_RATIO * (coderBytes + 1) + 1);
        lying[3] = (byte) (claimed >>> 24); lying[4] = (byte) (claimed >>> 16);
        lying[5] = (byte) (claimed >>> 8); lying[6] = (byte) claimed;
        assertEquals("length too large", assertThrows(CodecException.class, () -> Compressor.decompress(lying)).getMessage());
        // control: the largest allowed claim passes the ratio check and fails later, in the decoder or the CRC
        int allowed = Compressor.MAX_RATIO * (coderBytes + 1);
        lying[3] = (byte) (allowed >>> 24); lying[4] = (byte) (allowed >>> 16);
        lying[5] = (byte) (allowed >>> 8); lying[6] = (byte) allowed;
        String msg = assertThrows(CodecException.class, () -> Compressor.decompress(lying)).getMessage();
        assertNotEquals("length too large", msg);
    }

    @Test
    void streamWithNonZeroLeadingByteIsRejected() {
        byte[] good = Compressor.compress(text(1), Compressor.Engine.FREQ0);
        good[HEADER] = 1;
        assertEquals("bad stream start", assertThrows(CodecException.class, () -> Compressor.decompress(good)).getMessage());
    }

    @Test
    void orderTwoOnRandomDataStaysCloseToOrderOneBecauseOfTheContextCap() {
        byte[] in = random(99, 300_000);
        int f1 = Compressor.compress(in, Compressor.Engine.FREQ1).length;
        int f2 = Compressor.compress(in, Compressor.Engine.FREQ2).length;
        assertTrue(f2 < f1 * 1.01, "f1 " + f1 + " f2 " + f2);
    }

    @Test
    void contextCountIsCappedAndCountsDistinctPairs() {
        // "ab" repeated: pairs (0,0) start, then (a,b)... contexts seen are (0,0),(0,a),(a,b),(b,a) = 4 keys at most
        assertEquals(4, Compressor.order2ContextCount("abababab".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Compressor.MAX_CONTEXTS, Compressor.order2ContextCount(random(99, 300_000)));
    }
}
