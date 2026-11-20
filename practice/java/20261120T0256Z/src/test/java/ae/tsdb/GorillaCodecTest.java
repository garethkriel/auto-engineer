package ae.tsdb;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GorillaCodecTest {
    private static List<Point> pts(long... tsThenBits) {
        List<Point> l = new ArrayList<>();
        for (int i = 0; i < tsThenBits.length; i += 2) {
            l.add(new Point(tsThenBits[i], Double.longBitsToDouble(tsThenBits[i + 1])));
        }
        return l;
    }

    private static void assertRoundTrip(List<Point> in) {
        List<Point> out = GorillaCodec.decode(GorillaCodec.encode(in));
        assertEquals(in.size(), out.size());
        for (int i = 0; i < in.size(); i++) {
            assertEquals(in.get(i).ts(), out.get(i).ts(), "ts " + i);
            assertEquals(Double.doubleToRawLongBits(in.get(i).value()),
                    Double.doubleToRawLongBits(out.get(i).value()), "value bits " + i);
        }
    }

    @Test
    void emptyAndSinglePoint() {
        assertRoundTrip(List.of());
        assertRoundTrip(List.of(new Point(0, 0.0)));
        assertRoundTrip(List.of(new Point(Long.MAX_VALUE, -1.5)));
        assertEquals(4, GorillaCodec.encode(List.of()).length); // just the 32-bit count
    }

    @Test
    void regularSeriesWithConstantValueIsTiny() {
        List<Point> in = new ArrayList<>();
        for (int i = 0; i < 1000; i++) in.add(new Point(1_000_000L + 10L * i, 42.5));
        byte[] enc = GorillaCodec.encode(in);
        assertRoundTrip(in);
        // header 4 + first point 16 + second dod (10 + 7 bits) + 998 * 2 bits (dod 0, xor 0) + 1 bit for point 2's xor
        assertTrue(enc.length <= 4 + 16 + 3 + 250, "size was " + enc.length);
    }

    @Test
    void dodBucketBoundariesRoundTrip() {
        // deltas chosen so dod hits -63,64,-64,65,-255,256,-256,257,-2047,2048,-2048,2049 and huge values
        long[] deltas = {100, 37, 101, 37, 292, 37, 37 + 256, 1, 2300, 200, 5000, 2951, 1, Long.MAX_VALUE / 4, 1};
        List<Point> in = new ArrayList<>();
        long ts = 5;
        in.add(new Point(ts, 1.0));
        for (long d : deltas) {
            ts += d;
            in.add(new Point(ts, in.size()));
        }
        assertRoundTrip(in);
    }

    @Test
    void extremeTimestampsRoundTrip() {
        assertRoundTrip(List.of(new Point(0, 1), new Point(Long.MAX_VALUE, 2)));
        assertRoundTrip(List.of(new Point(0, 1), new Point(1, 2), new Point(Long.MAX_VALUE, 3)));
        assertRoundTrip(List.of(new Point(0, 1), new Point(Long.MAX_VALUE - 1, 2), new Point(Long.MAX_VALUE, 3)));
    }

    @Test
    void specialDoublesRoundTripBitExactly() {
        double[] vals = {0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_NORMAL, 1.0, 1.0000000000000002,
            Double.longBitsToDouble(0x7ff8000000000123L), Double.longBitsToDouble(0xfff0000000000001L),
            Double.longBitsToDouble(1L << 63), Double.longBitsToDouble(1L), Double.longBitsToDouble(-1L)};
        List<Point> in = new ArrayList<>();
        for (int i = 0; i < vals.length; i++) in.add(new Point(i * 7L, vals[i]));
        assertRoundTrip(in);
        // and every pair adjacent in both orders
        for (double a : vals) {
            for (double b : vals) assertRoundTrip(List.of(new Point(1, a), new Point(2, b), new Point(3, a)));
        }
    }

    @Test
    void xorWindowReuseAndReplacement() {
        // values chosen so the xor window first narrows, then fits (reuse), then does not fit (replace)
        long base = 0x4059000000000000L;
        long[] bits = {base, base ^ 0x0000000000F00000L, base ^ 0x0000000000300000L ^ 0x0000000000F00000L,
            base ^ 0x1L, base ^ 0x8000000000000000L, base, base ^ 0x0000FFFF00000000L, base ^ 0x0000FFFF00000001L};
        List<Point> in = new ArrayList<>();
        for (int i = 0; i < bits.length; i++) in.add(new Point(i, Double.longBitsToDouble(bits[i])));
        assertRoundTrip(in);
    }

    @Test
    void leadingZerosAboveThirtyOneAreCapped() {
        long a = 0x3FF0000000000000L;
        List<Point> in = List.of(new Point(1, Double.longBitsToDouble(a)),
                new Point(2, Double.longBitsToDouble(a ^ 1L)),          // 63 leading zeros, capped to 31
                new Point(3, Double.longBitsToDouble(a ^ 3L)));          // reuses window
        assertRoundTrip(in);
    }

    @Test
    void randomisedRoundTrips() {
        Random rnd = new Random(2026);
        for (int round = 0; round < 300; round++) {
            int n = rnd.nextInt(60);
            List<Point> in = new ArrayList<>();
            long ts = rnd.nextInt(3) == 0 ? 0 : rnd.nextLong() >>> 20;
            double v = rnd.nextDouble() * 100;
            for (int i = 0; i < n; i++) {
                switch (rnd.nextInt(4)) {
                    case 0 -> ts += 1 + rnd.nextInt(10);
                    case 1 -> ts += 1000;
                    case 2 -> ts += 1 + rnd.nextInt(5000);
                    default -> ts += 1 + (rnd.nextLong() >>> 45);
                }
                switch (rnd.nextInt(4)) {
                    case 0 -> { }
                    case 1 -> v += 0.25;
                    case 2 -> v = rnd.nextDouble();
                    default -> v = Double.longBitsToDouble(rnd.nextLong());
                }
                in.add(new Point(ts, v));
            }
            assertRoundTrip(in);
        }
    }

    @Test
    void encoderRejectsBadTimestamps() {
        assertThrows(IllegalArgumentException.class, () -> GorillaCodec.encode(List.of(new Point(-1, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> GorillaCodec.encode(List.of(new Point(5, 1), new Point(5, 2))));
        assertThrows(IllegalArgumentException.class,
                () -> GorillaCodec.encode(List.of(new Point(5, 1), new Point(9, 2), new Point(8, 3))));
    }

    @Test
    void decoderRejectsTruncationAndGarbage() {
        List<Point> in = new ArrayList<>();
        for (int i = 0; i < 40; i++) in.add(new Point(i * 13L, Math.sin(i)));
        byte[] enc = GorillaCodec.encode(in);
        for (int cut = 0; cut < enc.length; cut++) {
            final int c = cut;
            assertThrows(CorruptDataException.class, () -> GorillaCodec.decode(java.util.Arrays.copyOf(enc, c)),
                    "cut at " + c);
        }
        byte[] extra = java.util.Arrays.copyOf(enc, enc.length + 1);
        assertThrows(CorruptDataException.class, () -> GorillaCodec.decode(extra));
    }

    @Test
    void decoderRejectsImpossibleCount() {
        // count says 2^31 points but there is no data
        assertThrows(CorruptDataException.class, () -> GorillaCodec.decode(new byte[] {(byte) 0x80, 0, 0, 0}));
    }

    @Test
    void flippedBitsNeverCrashWithOtherThanCorruptData() {
        List<Point> in = new ArrayList<>();
        for (int i = 0; i < 30; i++) in.add(new Point(100L + i * 15L, 20 + (i % 5) * 0.5));
        byte[] enc = GorillaCodec.encode(in);
        for (int bit = 0; bit < enc.length * 8; bit++) {
            byte[] c = enc.clone();
            c[bit / 8] ^= (byte) (0x80 >> (bit % 8));
            try {
                List<Point> out = GorillaCodec.decode(c);
                assertNotNull(out); // allowed: a flip may decode to some other valid series
            } catch (CorruptDataException expected) {
                // fine
            }
        }
    }
}
