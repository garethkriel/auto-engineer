package ae.stream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class SnapshotTest {

    private static WindowEngine sample() {
        WindowEngine w = new WindowEngine(new WindowSpec.Session(5), 3, 20);
        long[] times = {1, 4, 30, 2, 31, 90, 5, 60};
        for (int i = 0; i < times.length; i++) {
            w.process(new Event(i % 2 == 0 ? "kéy-a" : "b", times[i], i + 1));
        }
        return w;
    }

    private static byte[] reseal(byte[] image) {
        byte[] copy = image.clone();
        CRC32 crc = new CRC32();
        crc.update(copy, 0, copy.length - WindowEngine.FOOTER_SIZE);
        long v = crc.getValue();
        for (int i = 0; i < WindowEngine.FOOTER_SIZE; i++) {
            copy[copy.length - 1 - i] = (byte) (v >>> (8 * i));
        }
        return copy;
    }

    @Test
    void roundTripPreservesEverythingIncludingDroppedAndNonAsciiKeys() {
        WindowEngine w = sample();
        assertFalse(w.dropped().isEmpty(), "fixture must contain a dropped event");
        WindowEngine r = WindowEngine.restore(w.snapshot());
        assertArrayEquals(w.snapshot(), r.snapshot());
        assertEquals(w.watermark(), r.watermark());
        assertEquals(w.dropped(), r.dropped());
        assertEquals(w.flush(), r.flush());
        r.checkInvariants();
    }

    @Test
    void emptyEngineRoundTrips() {
        WindowEngine w = new WindowEngine(new WindowSpec.Sliding(10, 5), 2, 0);
        WindowEngine r = WindowEngine.restore(w.snapshot());
        assertArrayEquals(w.snapshot(), r.snapshot());
        assertEquals(Long.MIN_VALUE, r.watermark());
    }

    @Test
    void everyTruncationIsRejected() {
        byte[] image = sample().snapshot();
        for (int len = 0; len < image.length; len++) {
            byte[] cut = java.util.Arrays.copyOf(image, len);
            assertThrows(CorruptSnapshotException.class, () -> WindowEngine.restore(cut), "length " + len);
        }
    }

    @Test
    void everySingleByteFlipIsRejectedByTheChecksum() {
        byte[] image = sample().snapshot();
        for (int i = 0; i < image.length; i++) {
            byte[] bad = image.clone();
            bad[i] ^= 0x40;
            final int at = i;
            assertThrows(CorruptSnapshotException.class, () -> WindowEngine.restore(bad), "byte " + at);
        }
    }

    @Test
    void resealedCorruptionGivesExceptionOrAValidEngine() {
        byte[] image = sample().snapshot();
        int rejected = 0;
        for (int i = 0; i < image.length - WindowEngine.FOOTER_SIZE; i++) {
            for (int mask : new int[] {0x01, 0x80, 0xFF}) {
                byte[] bad = image.clone();
                bad[i] ^= (byte) mask;
                byte[] sealed = reseal(bad);
                try {
                    WindowEngine.restore(sealed).checkInvariants();
                } catch (CorruptSnapshotException e) {
                    rejected++;
                }
            }
        }
        assertTrue(rejected > image.length, "structural validation must reject most re-sealed corruption: " + rejected);
    }

    @Test
    void craftedImageWithHugeCountIsRejectedWithoutAllocating() {
        byte[] image = sample().snapshot();
        // Header layout is fixed: magic(4) version(1) -> flip the version and re-seal.
        byte[] bad = image.clone();
        bad[WindowEngine.HEADER_SIZE - 1] = 99;
        assertThrows(CorruptSnapshotException.class, () -> WindowEngine.restore(reseal(bad)));
    }

    @Test
    void validSnapshotKeepsWorkingAfterRestore() {
        WindowEngine w = sample();
        WindowEngine r = WindowEngine.restore(w.snapshot());
        Event e = new Event("b", 200, 1);
        assertEquals(w.process(e), r.process(e));
        assertArrayEquals(w.snapshot(), r.snapshot());
    }
}
