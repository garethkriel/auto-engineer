package ae.sketch;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CountMinSketchTest {
    @Test
    void sizingMatchesLiterals() {
        // width = ceil(e / 0.01) = ceil(271.83) = 272; depth = ceil(ln 100) = ceil(4.605) = 5
        CountMinSketch s = CountMinSketch.forErrorBounds(0.01, 0.01, false);
        assertEquals(272, s.width());
        assertEquals(5, s.depth());
    }

    @Test
    void exactWhenThereAreNoCollisions() {
        CountMinSketch s = CountMinSketch.withShape(1 << 16, 4, false);
        s.add("a", 5);
        s.add("a", 2);
        s.add("b", 1);
        assertEquals(7, s.estimate("a"));
        assertEquals(1, s.estimate("b"));
        assertEquals(0, s.estimate("never"));
        assertEquals(8, s.total());
    }

    @Test
    void neverUnderestimatesAndStaysWithinBound() {
        for (boolean conservative : new boolean[] {false, true}) {
            CountMinSketch s = CountMinSketch.forErrorBounds(0.01, 0.01, conservative);
            Map<String, Long> truth = new HashMap<>();
            Random rnd = new Random(42);
            for (int i = 0; i < 50_000; i++) {
                String key = "k" + (int) Math.abs(rnd.nextGaussian() * 300);
                long c = 1 + rnd.nextInt(3);
                s.add(key, c);
                truth.merge(key, c, Long::sum);
            }
            long bound = (long) Math.ceil(0.01 * s.total());
            int overBound = 0;
            for (Map.Entry<String, Long> e : truth.entrySet()) {
                long est = s.estimate(e.getKey());
                assertTrue(est >= e.getValue(), "underestimate for " + e.getKey() + " conservative=" + conservative);
                if (est - e.getValue() > bound) {
                    overBound++;
                }
            }
            // delta = 1%: allow at most 3% of keys to exceed the eps bound (seeded, deterministic)
            assertTrue(overBound <= truth.size() * 0.03, overBound + " of " + truth.size() + " over bound");
        }
    }

    @Test
    void conservativeUpdateIsNeverWorseThanPlain() {
        CountMinSketch plain = CountMinSketch.withShape(50, 3, false);
        CountMinSketch cons = CountMinSketch.withShape(50, 3, true);
        Random rnd = new Random(7);
        String[] keys = new String[400];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = "q" + i;
        }
        for (int i = 0; i < 5000; i++) {
            String k = keys[rnd.nextInt(keys.length)];
            plain.add(k, 1);
            cons.add(k, 1);
        }
        boolean strictlyBetterSomewhere = false;
        for (String k : keys) {
            assertTrue(cons.estimate(k) <= plain.estimate(k), k);
            strictlyBetterSomewhere |= cons.estimate(k) < plain.estimate(k);
        }
        assertTrue(strictlyBetterSomewhere, "conservative update should help on a crowded table");
    }

    @Test
    void mergeEqualsSketchOfBothStreamsAndIsCommutative() {
        CountMinSketch a = CountMinSketch.withShape(64, 4, false);
        CountMinSketch b = CountMinSketch.withShape(64, 4, false);
        CountMinSketch both = CountMinSketch.withShape(64, 4, false);
        for (int i = 0; i < 200; i++) {
            a.add("x" + i % 17, 2);
            both.add("x" + i % 17, 2);
            b.add("y" + i % 11, 3);
            both.add("y" + i % 11, 3);
        }
        CountMinSketch ab = CountMinSketch.withShape(64, 4, false);
        ab.merge(a);
        ab.merge(b);
        CountMinSketch ba = CountMinSketch.withShape(64, 4, false);
        ba.merge(b);
        ba.merge(a);
        assertEquals(both, ab);
        assertEquals(ab, ba);
        assertEquals(1000, ab.total());
    }

    @Test
    void mergeRejectsShapeMismatchWithoutChangingTarget() {
        CountMinSketch a = CountMinSketch.withShape(8, 2, false);
        a.add("k", 1);
        CountMinSketch before = CountMinSketch.fromBytes(a.toBytes());
        assertThrows(IllegalArgumentException.class, () -> a.merge(CountMinSketch.withShape(9, 2, false)));
        assertThrows(IllegalArgumentException.class, () -> a.merge(CountMinSketch.withShape(8, 3, false)));
        assertEquals(before, a);
    }

    @Test
    void overflowLeavesSketchUnchanged() {
        for (boolean conservative : new boolean[] {false, true}) {
            CountMinSketch s = CountMinSketch.withShape(4, 2, conservative);
            s.add("a", Long.MAX_VALUE - 1);
            CountMinSketch before = CountMinSketch.fromBytes(s.toBytes());
            assertThrows(ArithmeticException.class, () -> s.add("a", 2));
            assertEquals(before, s, "state changed after failed add, conservative=" + conservative);
        }
    }

    @Test
    void mergeOverflowLeavesTargetUnchanged() {
        CountMinSketch a = CountMinSketch.withShape(4, 2, false);
        CountMinSketch b = CountMinSketch.withShape(4, 2, false);
        a.add("k", Long.MAX_VALUE - 1);
        b.add("k", 5);
        CountMinSketch before = CountMinSketch.fromBytes(a.toBytes());
        assertThrows(ArithmeticException.class, () -> a.merge(b));
        assertEquals(before, a);
    }

    @Test
    void invalidArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.forErrorBounds(0, 0.1, false));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.forErrorBounds(0.1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.forErrorBounds(1e-12, 0.1, false));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.withShape(0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.withShape(1, 0, false));
        CountMinSketch s = CountMinSketch.withShape(4, 2, false);
        assertThrows(IllegalArgumentException.class, () -> s.add("a", -1));
        assertThrows(NullPointerException.class, () -> s.add((String) null, 1));
    }

    @Test
    void roundTripAndCorruption() {
        CountMinSketch s = CountMinSketch.withShape(16, 3, true);
        for (int i = 0; i < 100; i++) {
            s.add("z" + i, i);
        }
        byte[] img = s.toBytes();
        assertEquals(s, CountMinSketch.fromBytes(img));
        for (int len = 0; len < img.length; len++) {
            byte[] cut = java.util.Arrays.copyOf(img, len);
            assertThrows(IllegalArgumentException.class, () -> CountMinSketch.fromBytes(cut), "length " + len);
        }
        for (int i = 0; i < img.length; i++) {
            byte[] bad = img.clone();
            bad[i] ^= 0x01;
            final int at = i;
            assertThrows(IllegalArgumentException.class, () -> CountMinSketch.fromBytes(bad), "flip at " + at);
        }
    }

    @Test
    void resealedNegativeCounterOrBadFlagIsRejected() {
        ByteBuffer neg = ByteBuffer.allocate(4 + 4 + 4 + 1 + 8 + 8);
        neg.putInt(CountMinSketch.MAGIC).putInt(1).putInt(1).put((byte) 0).putLong(0).putLong(-1);
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.fromBytes(Codec.seal(neg.array())));
        ByteBuffer flag = ByteBuffer.allocate(4 + 4 + 4 + 1 + 8 + 8);
        flag.putInt(CountMinSketch.MAGIC).putInt(1).putInt(1).put((byte) 2).putLong(0).putLong(0);
        assertThrows(IllegalArgumentException.class, () -> CountMinSketch.fromBytes(Codec.seal(flag.array())));
        ByteBuffer ok = ByteBuffer.allocate(4 + 4 + 4 + 1 + 8 + 8);
        ok.putInt(CountMinSketch.MAGIC).putInt(1).putInt(1).put((byte) 0).putLong(3).putLong(3);
        assertEquals(3, CountMinSketch.fromBytes(Codec.seal(ok.array())).estimate("anything"));
    }
}
