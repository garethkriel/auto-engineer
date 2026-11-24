package ae.rangecoder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RangeCoderTest {

    @Test
    void emptyStreamIsFiveBytesAndDecoderAcceptsIt() {
        RangeEncoder e = new RangeEncoder();
        byte[] out = e.finish();
        assertEquals(5, out.length);
        assertEquals(0, out[0]);   // LZMA-style leading zero byte
        new RangeDecoder(out, 0);  // must not throw
    }

    @Test
    void singleSymbolRoundTrip() {
        RangeEncoder e = new RangeEncoder();
        e.encode(3, 2, 10);
        RangeDecoder d = new RangeDecoder(e.finish(), 0);
        int t = d.target(10);
        assertTrue(t >= 3 && t < 5, "target " + t);
        d.consume(3, 2, 10);
    }

    /** Encodes a random list of (cum,freq,total) triples and decodes them back by target. */
    private static void roundTrip(long seed, int count, int[] totals, boolean extreme) {
        Random r = new Random(seed);
        int[][] ops = new int[count][];
        RangeEncoder e = new RangeEncoder();
        for (int i = 0; i < count; i++) {
            int total = totals[r.nextInt(totals.length)];
            int freq;
            if (extreme) {
                switch (r.nextInt(3)) {
                    case 0 -> freq = 1;
                    case 1 -> freq = total - 1 < 1 ? 1 : total - 1;
                    default -> freq = 1 + r.nextInt(total);
                }
            } else {
                freq = 1 + r.nextInt(total);
            }
            int cum = r.nextInt(total - freq + 1);
            if (extreme && r.nextBoolean()) cum = r.nextBoolean() ? 0 : total - freq;
            ops[i] = new int[] {cum, freq, total};
            e.encode(cum, freq, total);
        }
        byte[] data = e.finish();
        RangeDecoder d = new RangeDecoder(data, 0);
        for (int i = 0; i < count; i++) {
            int cum = ops[i][0], freq = ops[i][1], total = ops[i][2];
            int t = d.target(total);
            assertTrue(cum <= t && t < cum + freq, "op " + i + " seed " + seed + " target " + t
                    + " not in [" + cum + "," + (cum + freq) + ")");
            d.consume(cum, freq, total);
        }
    }

    @Test
    void randomFrequencyStreamsRoundTrip() {
        for (long seed = 1; seed <= 20; seed++) {
            roundTrip(seed, 2000, new int[] {1, 2, 3, 255, 256, 1000, 4096, 65535, 65536}, false);
        }
    }

    @Test
    void extremeFrequenciesRoundTrip() {
        for (long seed = 100; seed < 130; seed++) {
            roundTrip(seed, 3000, new int[] {2, 65536, 65535, 3}, true);
        }
    }

    @Test
    void carryPropagationHappensAndIsCorrect() {
        // outcome-mix measurement: carries must occur across the seeded runs, otherwise the test proves nothing
        int totalCarries = 0;
        for (long seed = 200; seed < 260; seed++) {
            Random r = new Random(seed);
            RangeEncoder e = new RangeEncoder();
            int n = 4000;
            int[] cums = new int[n];
            int[] freqs = new int[n];
            for (int i = 0; i < n; i++) {
                // wide intervals in the upper half of the range push low across the 2^32 boundary
                freqs[i] = 1 + r.nextInt(32768);
                cums[i] = 65536 - freqs[i] - r.nextInt(Math.min(1000, 65536 - freqs[i] + 1));
                e.encode(cums[i], freqs[i], 65536);
            }
            totalCarries += e.carries();
            RangeDecoder d = new RangeDecoder(e.finish(), 0);
            for (int i = 0; i < n; i++) {
                int t = d.target(65536);
                assertTrue(t >= cums[i] && t < cums[i] + freqs[i], "seed " + seed + " i " + i);
                d.consume(cums[i], freqs[i], 65536);
            }
        }
        assertTrue(totalCarries > 0, "no carry was ever produced");
    }

    @Test
    void highlyPredictableSymbolsCostAlmostNothing() {
        RangeEncoder e = new RangeEncoder();
        for (int i = 0; i < 100_000; i++) e.encode(0, 65535, 65536);
        // 100000 * -log2(65535/65536) = ~2.2 bits in total
        assertTrue(e.finish().length <= 10);
    }

    @Test
    void bitModelAdaptsTowardObservedBit() {
        BitModel m = new BitModel();
        assertEquals(BitModel.ONE_HALF, m.probZero());
        RangeEncoder e = new RangeEncoder();
        for (int i = 0; i < 50; i++) e.encodeBit(m, 0);
        assertTrue(m.probZero() > BitModel.ONE_HALF);
        assertTrue(m.probZero() < BitModel.SCALE);
        BitModel m2 = new BitModel();
        for (int i = 0; i < 50; i++) e.encodeBit(m2, 1);
        assertTrue(m2.probZero() < BitModel.ONE_HALF);
        assertTrue(m2.probZero() > 0);
    }

    @Test
    void bitStreamRoundTripWithSkewedBits() {
        Random r = new Random(77);
        int n = 50_000;
        int[] bits = new int[n];
        BitModel em = new BitModel();
        RangeEncoder e = new RangeEncoder();
        for (int i = 0; i < n; i++) {
            bits[i] = r.nextInt(10) == 0 ? 1 : 0;
            e.encodeBit(em, bits[i]);
        }
        byte[] data = e.finish();
        // H(0.1) = 0.469 bits per bit -> ~2930 bytes; adaptive coder must be close
        assertTrue(data.length < 3200, "size " + data.length);
        assertTrue(data.length > 2700, "suspiciously small " + data.length);
        BitModel dm = new BitModel();
        RangeDecoder d = new RangeDecoder(data, 0);
        for (int i = 0; i < n; i++) assertEquals(bits[i], d.decodeBit(dm), "bit " + i);
    }

    @Test
    void frequencyAndBitCodingInterleaveInOneStream() {
        Random r = new Random(9);
        int n = 5000;
        boolean[] isBit = new boolean[n];
        int[] val = new int[n];
        RangeEncoder e = new RangeEncoder();
        BitModel em = new BitModel();
        FenwickModel fm = new FenwickModel(16);
        for (int i = 0; i < n; i++) {
            isBit[i] = r.nextBoolean();
            if (isBit[i]) {
                val[i] = r.nextInt(5) == 0 ? 1 : 0;
                e.encodeBit(em, val[i]);
            } else {
                val[i] = Math.min(15, (int) Math.abs(r.nextGaussian() * 3));
                e.encode(fm.cumFreq(val[i]), fm.freq(val[i]), fm.total());
                fm.update(val[i]);
            }
        }
        RangeDecoder d = new RangeDecoder(e.finish(), 0);
        BitModel dm = new BitModel();
        FenwickModel dfm = new FenwickModel(16);
        for (int i = 0; i < n; i++) {
            if (isBit[i]) {
                assertEquals(val[i], d.decodeBit(dm), "bit at " + i);
            } else {
                int s = dfm.findSymbol(d.target(dfm.total()));
                d.consume(dfm.cumFreq(s), dfm.freq(s), dfm.total());
                dfm.update(s);
                assertEquals(val[i], s, "symbol at " + i);
            }
        }
    }

    @Test
    void decoderReadsFromAnOffset() {
        RangeEncoder e = new RangeEncoder();
        e.encode(5, 1, 7);
        byte[] body = e.finish();
        byte[] withPrefix = new byte[body.length + 3];
        withPrefix[0] = 42; withPrefix[1] = 43; withPrefix[2] = 44;
        System.arraycopy(body, 0, withPrefix, 3, body.length);
        RangeDecoder d = new RangeDecoder(withPrefix, 3);
        assertEquals(5, d.target(7));
    }

    @Test
    void overReadAndImpossibleTargetsThrow() {
        assertThrows(CodecException.class, () -> new RangeDecoder(new byte[4], 0));
        RangeEncoder e = new RangeEncoder();
        for (int i = 0; i < 100; i++) e.encode(i % 7, 1, 7);
        byte[] data = e.finish();
        byte[] cut = java.util.Arrays.copyOf(data, data.length - 3);
        RangeDecoder d = new RangeDecoder(cut, 0);
        assertThrows(CodecException.class, () -> {
            for (int i = 0; i < 100; i++) {
                int t = d.target(7);
                d.consume(t, 1, 7);
            }
        });
        assertThrows(IllegalArgumentException.class, () -> new RangeEncoder().encode(0, 1, 65537));
        assertThrows(IllegalArgumentException.class, () -> new RangeEncoder().encode(0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new RangeEncoder().encode(9, 2, 10));
        assertThrows(IllegalArgumentException.class, () -> new RangeEncoder().encodeBit(new BitModel(), 2));
    }

    @Test
    void finishTwiceIsRejected() {
        RangeEncoder e = new RangeEncoder();
        e.finish();
        assertThrows(IllegalStateException.class, e::finish);
        assertThrows(IllegalStateException.class, () -> e.encode(0, 1, 2));
    }
}
