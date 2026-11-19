package ae.sketch;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class HyperLogLogTest {
    @Test
    void emptyEstimatesZero() {
        assertEquals(0.0, HyperLogLog.create(10).estimate(), 0.0);
    }

    @Test
    void standardErrorLiterals() {
        assertEquals(1.04 / 128, HyperLogLog.create(14).standardError(), 1e-12);
        assertEquals(0.26, HyperLogLog.create(4).standardError(), 1e-12); // 1.04 / sqrt(16)
    }

    @Test
    void duplicatesDoNotChangeTheSketch() {
        HyperLogLog once = HyperLogLog.create(12);
        HyperLogLog many = HyperLogLog.create(12);
        for (int i = 0; i < 1000; i++) {
            once.add("u" + i);
            for (int j = 0; j < 5; j++) {
                many.add("u" + i);
            }
        }
        assertEquals(once, many);
    }

    @Test
    void smallCardinalitiesUseLinearCountingAndAreNearExact() {
        HyperLogLog h = HyperLogLog.create(14);
        for (int i = 0; i < 100; i++) {
            h.add("s" + i);
        }
        assertEquals(100, h.estimate(), 3);
    }

    @Test
    void estimateWithinThreeStandardErrorsAcrossScales() {
        int[] sizes = {1_000, 20_000, 200_000};
        for (int n : sizes) {
            HyperLogLog h = HyperLogLog.create(14);
            for (int i = 0; i < n; i++) {
                h.add("item-" + i);
            }
            double tol = 3 * h.standardError() * n;
            assertEquals(n, h.estimate(), tol, "n=" + n);
        }
    }

    @Test
    void lowPrecisionStillWithinItsWiderBound() {
        HyperLogLog h = HyperLogLog.create(6);
        for (int i = 0; i < 50_000; i++) {
            h.add("p6-" + i);
        }
        assertEquals(50_000, h.estimate(), 3 * h.standardError() * 50_000);
    }

    @Test
    void mergeEqualsSketchOfUnionAndIsCommutativeAndIdempotent() {
        HyperLogLog a = HyperLogLog.create(10);
        HyperLogLog b = HyperLogLog.create(10);
        HyperLogLog union = HyperLogLog.create(10);
        for (int i = 0; i < 3000; i++) {
            a.add("a" + i);
            union.add("a" + i);
        }
        for (int i = 1500; i < 6000; i++) { // overlaps a on 1500..2999 through shared "a" keys
            String k = i < 3000 ? "a" + i : "b" + i;
            b.add(k);
            union.add(k);
        }
        HyperLogLog ab = HyperLogLog.create(10);
        ab.merge(a);
        ab.merge(b);
        HyperLogLog ba = HyperLogLog.create(10);
        ba.merge(b);
        ba.merge(a);
        assertEquals(union, ab);
        assertEquals(ab, ba);
        HyperLogLog twice = HyperLogLog.create(10);
        twice.merge(ab);
        twice.merge(ab);
        assertEquals(ab, twice);
        // distinct keys: a0..a2999 plus b3000..b5999 = 6000
        assertEquals(6000, ab.estimate(), 3 * ab.standardError() * 6000);
    }

    @Test
    void mergeRejectsPrecisionMismatch() {
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.create(8).merge(HyperLogLog.create(9)));
        assertThrows(NullPointerException.class, () -> HyperLogLog.create(8).merge(null));
    }

    @Test
    void precisionBoundsEnforced() {
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.create(3));
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.create(17));
        assertEquals(4, HyperLogLog.create(4).precision());
        assertEquals(16, HyperLogLog.create(16).precision());
    }

    @Test
    void roundTripAndCorruption() {
        HyperLogLog h = HyperLogLog.create(5);
        for (int i = 0; i < 500; i++) {
            h.add("r" + i);
        }
        byte[] img = h.toBytes();
        assertEquals(h, HyperLogLog.fromBytes(img));
        for (int len = 0; len < img.length; len++) {
            byte[] cut = java.util.Arrays.copyOf(img, len);
            assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(cut), "length " + len);
        }
        for (int i = 0; i < img.length; i++) {
            byte[] bad = img.clone();
            bad[i] ^= (byte) 0x80;
            final int at = i;
            assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(bad), "flip at " + at);
        }
    }

    @Test
    void resealedBadPrecisionOrRegisterIsRejected() {
        ByteBuffer badP = ByteBuffer.allocate(4 + 1 + 8);
        badP.putInt(HyperLogLog_MAGIC()).put((byte) 3);
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(Codec.seal(badP.array())));
        // p=4 -> 16 registers, max legal rank is 64-4+1 = 61
        ByteBuffer over = ByteBuffer.allocate(4 + 1 + 16);
        over.putInt(HyperLogLog_MAGIC()).put((byte) 4);
        byte[] raw = over.array();
        raw[5] = 62;
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(Codec.seal(raw)));
        raw[5] = 61;
        assertEquals(16, HyperLogLog.fromBytes(Codec.seal(raw)).toBytes().length - 4 - 1 - Codec.CRC_SIZE);
        raw[5] = -1;
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(Codec.seal(raw)));
    }

    private static int HyperLogLog_MAGIC() {
        return HyperLogLog.MAGIC;
    }
}
