package ae.sketch;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class BloomFilterTest {
    @Test
    void sizingMatchesTextbookLiterals() {
        // m = ceil(1000 * 4.60517 / 0.480453) = 9586, k = round(9.586 * 0.6931) = 7
        BloomFilter f = BloomFilter.forExpected(1000, 0.01);
        assertEquals(9586, f.bitCount());
        assertEquals(7, f.hashCount());
        // fpp 0.5 on 1 item: m = ceil(0.6931/0.4805) = 2, k = round(2*0.6931) = 1
        BloomFilter tiny = BloomFilter.forExpected(1, 0.5);
        assertEquals(2, tiny.bitCount());
        assertEquals(1, tiny.hashCount());
    }

    @Test
    void emptyFilterContainsNothing() {
        BloomFilter f = BloomFilter.forExpected(100, 0.01);
        assertFalse(f.mightContain("a"));
        assertEquals(0, f.setBits());
        assertEquals(0.0, f.estimatedCount(), 0.0);
    }

    @Test
    void neverReportsAFalseNegative() {
        BloomFilter f = BloomFilter.forExpected(5000, 0.01);
        for (int i = 0; i < 5000; i++) {
            f.add("member-" + i);
        }
        for (int i = 0; i < 5000; i++) {
            assertTrue(f.mightContain("member-" + i), "lost member-" + i);
        }
    }

    @Test
    void measuredFalsePositiveRateStaysNearTarget() {
        BloomFilter f = BloomFilter.forExpected(10_000, 0.01);
        for (int i = 0; i < 10_000; i++) {
            f.add("in-" + i);
        }
        int fp = 0;
        int probes = 20_000;
        for (int i = 0; i < probes; i++) {
            if (f.mightContain("out-" + i)) {
                fp++;
            }
        }
        double rate = fp / (double) probes;
        assertTrue(rate < 0.02, "false positive rate " + rate);
        assertTrue(rate > 0.001, "suspiciously low rate " + rate + " (filter may be degenerate)");
    }

    @Test
    void cardinalityEstimateIsClose() {
        BloomFilter f = BloomFilter.forExpected(10_000, 0.01);
        for (int i = 0; i < 8000; i++) {
            f.add("k" + i);
            f.add("k" + i); // duplicates must not matter
        }
        assertEquals(8000, f.estimatedCount(), 8000 * 0.03);
    }

    @Test
    void saturatedFilterEstimatesInfinity() {
        BloomFilter f = BloomFilter.withShape(8, 3);
        for (int i = 0; i < 200; i++) {
            f.add("x" + i);
        }
        assertEquals(8, f.setBits());
        assertEquals(Double.POSITIVE_INFINITY, f.estimatedCount());
    }

    @Test
    void unionEqualsFilterOfBothStreamsAndIsCommutative() {
        BloomFilter a = BloomFilter.forExpected(1000, 0.01);
        BloomFilter b = BloomFilter.forExpected(1000, 0.01);
        BloomFilter both = BloomFilter.forExpected(1000, 0.01);
        for (int i = 0; i < 300; i++) {
            a.add("a" + i);
            both.add("a" + i);
        }
        for (int i = 0; i < 300; i++) {
            b.add("b" + i);
            both.add("b" + i);
        }
        assertEquals(both, a.union(b));
        assertEquals(a.union(b), b.union(a));
        assertEquals(a, a.union(a));
    }

    @Test
    void unionRejectsDifferentShape() {
        assertThrows(IllegalArgumentException.class,
            () -> BloomFilter.withShape(64, 3).union(BloomFilter.withShape(65, 3)));
        assertThrows(IllegalArgumentException.class,
            () -> BloomFilter.withShape(64, 3).union(BloomFilter.withShape(64, 4)));
    }

    @Test
    void invalidArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forExpected(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forExpected(10, 0.0));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forExpected(10, 1.0));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forExpected(10, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forExpected(Long.MAX_VALUE, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.withShape(0, 1));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.withShape(1, 0));
        assertThrows(NullPointerException.class, () -> BloomFilter.withShape(8, 1).add((String) null));
    }

    @Test
    void roundTripPreservesEverything() {
        BloomFilter f = BloomFilter.forExpected(500, 0.02);
        for (int i = 0; i < 500; i++) {
            f.add("v" + i);
        }
        BloomFilter g = BloomFilter.fromBytes(f.toBytes());
        assertEquals(f, g);
        assertEquals(f.hashCode(), g.hashCode());
        assertTrue(g.mightContain("v123"));
    }

    @Test
    void everyTruncationIsRejected() {
        byte[] img = BloomFilter.withShape(100, 3).toBytes();
        for (int len = 0; len < img.length; len++) {
            byte[] cut = java.util.Arrays.copyOf(img, len);
            assertThrows(IllegalArgumentException.class, () -> BloomFilter.fromBytes(cut), "length " + len);
        }
    }

    @Test
    void everySingleByteFlipIsRejected() {
        BloomFilter f = BloomFilter.withShape(100, 3);
        f.add("x");
        byte[] img = f.toBytes();
        for (int i = 0; i < img.length; i++) {
            byte[] bad = img.clone();
            bad[i] ^= 0x40;
            final int at = i;
            assertThrows(IllegalArgumentException.class, () -> BloomFilter.fromBytes(bad), "flip at " + at);
        }
    }

    @Test
    void resealedImageWithStrayBitsBeyondTheSizeIsRejected() {
        // 70 bits -> 2 words; bit 70 and above of word 1 must be zero
        ByteBuffer b = ByteBuffer.allocate(4 + 8 + 16);
        b.putInt(BloomFilter.MAGIC).putInt(70).putInt(2).putLong(0).putLong(1L << 10);
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.fromBytes(Codec.seal(b.array())));
        ByteBuffer ok = ByteBuffer.allocate(4 + 8 + 16);
        ok.putInt(BloomFilter.MAGIC).putInt(70).putInt(2).putLong(0).putLong(1L << 5);
        assertEquals(1, BloomFilter.fromBytes(Codec.seal(ok.array())).setBits());
    }

    @Test
    void resealedImageWithWrongMagicOrShapeIsRejected() {
        ByteBuffer wrongMagic = ByteBuffer.allocate(4 + 8 + 8);
        wrongMagic.putInt(0xdeadbeef).putInt(64).putInt(2).putLong(0);
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.fromBytes(Codec.seal(wrongMagic.array())));
        ByteBuffer zeroK = ByteBuffer.allocate(4 + 8 + 8);
        zeroK.putInt(BloomFilter.MAGIC).putInt(64).putInt(0).putLong(0);
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.fromBytes(Codec.seal(zeroK.array())));
        ByteBuffer extra = ByteBuffer.allocate(4 + 8 + 16);
        extra.putInt(BloomFilter.MAGIC).putInt(64).putInt(2).putLong(0).putLong(0);
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.fromBytes(Codec.seal(extra.array())));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.fromBytes(null));
    }
}
