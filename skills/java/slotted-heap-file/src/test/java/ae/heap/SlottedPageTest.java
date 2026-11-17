package ae.heap;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class SlottedPageTest {
    private static byte[] b(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    @Test
    void rejectsBadPageSize() {
        assertThrows(IllegalArgumentException.class, () -> new SlottedPage(63));
        assertThrows(IllegalArgumentException.class, () -> new SlottedPage(32769));
        assertDoesNotThrow(() -> new SlottedPage(64));
        assertDoesNotThrow(() -> new SlottedPage(32768));
    }

    @Test
    void insertAndGetRoundTrip() {
        SlottedPage p = new SlottedPage(128);
        assertEquals(0, p.insert(b("alpha")));
        assertEquals(1, p.insert(b("")));
        assertEquals(2, p.insert(b("gamma")));
        assertArrayEquals(b("alpha"), p.get(0).orElseThrow());
        assertArrayEquals(new byte[0], p.get(1).orElseThrow());
        assertArrayEquals(b("gamma"), p.get(2).orElseThrow());
        assertEquals(3, p.liveCount());
        assertThrows(IllegalArgumentException.class, () -> p.get(3));
        assertThrows(IllegalArgumentException.class, () -> p.get(-1));
    }

    @Test
    void freeSpaceAccounting() {
        SlottedPage p = new SlottedPage(64);
        // 64 - 4 (crc) - 4 (header) = 56 usable; a new record costs len + 4 for its slot
        assertEquals(56 - 4, p.freeSpace());
        p.insert(new byte[10]);
        assertEquals(56 - 4 - 14, p.freeSpace());
    }

    @Test
    void exactFitAndOneByteTooBig() {
        SlottedPage p = new SlottedPage(64);
        assertEquals(-1, p.insert(new byte[53]));   // 53 + 4 slot = 57 > 56
        assertEquals(0, p.insert(new byte[52]));    // 52 + 4 = 56 exactly
        assertEquals(0, p.freeSpace());
        assertEquals(-1, p.insert(new byte[0]));    // not even a slot fits
    }

    @Test
    void deleteReusesSlotAndReportsRepeat() {
        SlottedPage p = new SlottedPage(128);
        p.insert(b("a")); p.insert(b("b")); p.insert(b("c"));
        assertTrue(p.delete(1));
        assertFalse(p.delete(1));
        assertTrue(p.get(1).isEmpty());
        assertEquals(2, p.liveCount());
        assertEquals(1, p.insert(b("new")));
        assertArrayEquals(b("new"), p.get(1).orElseThrow());
        assertArrayEquals(b("c"), p.get(2).orElseThrow());
    }

    @Test
    void insertCompactsWhenHolesMakeItFit() {
        SlottedPage p = new SlottedPage(64);
        int a = p.insert(new byte[20]);
        int c = p.insert(new byte[20]);   // 2 * 24 = 48, 8 left
        assertEquals(-1, p.insert(new byte[12]));
        p.delete(a);                      // hole of 20 at the end of data area, not contiguous
        byte[] big = new byte[28];
        Arrays.fill(big, (byte) 7);
        // reuses slot a: needs 28 <= 8 + 20 after compaction
        assertEquals(a, p.insert(big));
        assertArrayEquals(big, p.get(a).orElseThrow());
        assertEquals(20, p.get(c).orElseThrow().length);
    }

    @Test
    void updateShrinkGrowAndFailureIsSideEffectFree() {
        SlottedPage p = new SlottedPage(64);
        p.insert(b("0123456789"));
        p.insert(b("abcdefghij"));
        assertTrue(p.update(0, b("xy")));
        assertArrayEquals(b("xy"), p.get(0).orElseThrow());
        assertTrue(p.update(0, b("0123456789ABCDEFGHIJ")));   // grows, fits after compaction
        assertArrayEquals(b("0123456789ABCDEFGHIJ"), p.get(0).orElseThrow());
        assertArrayEquals(b("abcdefghij"), p.get(1).orElseThrow());
        byte[] before = p.toBytes();
        assertFalse(p.update(0, new byte[40]));
        assertArrayEquals(before, p.toBytes());
        p.delete(1);
        assertFalse(p.update(1, b("z")));
        assertThrows(IllegalArgumentException.class, () -> p.update(5, b("z")));
    }

    @Test
    void serialisationRoundTrip() throws Exception {
        SlottedPage p = new SlottedPage(256);
        for (int i = 0; i < 10; i++) p.insert(b("rec-" + i));
        p.delete(3);
        p.update(4, b("changed-longer-record"));
        SlottedPage q = SlottedPage.fromBytes(p.toBytes());
        assertEquals(p.liveCount(), q.liveCount());
        for (int i = 0; i < 10; i++) {
            assertEquals(p.get(i).map(Arrays::toString), q.get(i).map(Arrays::toString));
        }
        assertArrayEquals(p.toBytes(), q.toBytes());
    }

    @Test
    void flippedByteIsDetectedEverywhere() {
        SlottedPage p = new SlottedPage(64);
        p.insert(b("hello"));
        byte[] good = p.toBytes();
        for (int i = 0; i < good.length; i++) {
            byte[] bad = good.clone();
            bad[i] ^= 0x01;
            int idx = i;
            assertThrows(CorruptDataException.class, () -> SlottedPage.fromBytes(bad), "byte " + idx);
        }
    }

    @Test
    void wrongLengthRejected() {
        assertThrows(CorruptDataException.class, () -> SlottedPage.fromBytes(new byte[10]));
        assertThrows(CorruptDataException.class, () -> SlottedPage.fromBytes(new byte[64 + 1]));
    }

    /** Valid CRC but two slots pointing at the same bytes. */
    @Test
    void overlappingSlotsWithValidCrcRejected() {
        byte[] page = new byte[64];
        ByteBuffer bb = ByteBuffer.wrap(page);
        bb.putShort(0, (short) 2);
        bb.putShort(2, (short) 50);
        bb.putShort(4, (short) 50); bb.putShort(6, (short) 10);
        bb.putShort(8, (short) 55); bb.putShort(10, (short) 5);   // 55..60 overlaps 50..60
        seal(page);
        assertThrows(CorruptDataException.class, () -> SlottedPage.fromBytes(page));
    }

    @Test
    void slotOutOfBoundsWithValidCrcRejected() {
        byte[] page = new byte[64];
        ByteBuffer bb = ByteBuffer.wrap(page);
        bb.putShort(0, (short) 1);
        bb.putShort(2, (short) 40);
        bb.putShort(4, (short) 50); bb.putShort(6, (short) 11);   // 50+11 = 61 > 60
        seal(page);
        assertThrows(CorruptDataException.class, () -> SlottedPage.fromBytes(page));
    }

    @Test
    void emptyPageValid() throws Exception {
        SlottedPage p = SlottedPage.fromBytes(new SlottedPage(64).toBytes());
        assertEquals(0, p.liveCount());
    }

    static void seal(byte[] page) {
        CRC32 c = new CRC32();
        c.update(page, 0, page.length - 4);
        ByteBuffer.wrap(page).putInt(page.length - 4, (int) c.getValue());
    }

    @Test
    void randomisedModelCheck() throws Exception {
        Random rnd = new Random(42);
        for (int round = 0; round < 50; round++) {
            SlottedPage p = new SlottedPage(256);
            Map<Integer, byte[]> model = new HashMap<>();
            for (int step = 0; step < 300; step++) {
                int op = rnd.nextInt(4);
                if (op == 0) {
                    byte[] d = new byte[rnd.nextInt(40)];
                    rnd.nextBytes(d);
                    int s = p.insert(d);
                    if (s >= 0) { assertNull(model.put(s, d)); }
                } else if (op == 1 && !model.isEmpty()) {
                    int s = new ArrayList<>(model.keySet()).get(rnd.nextInt(model.size()));
                    assertTrue(p.delete(s));
                    model.remove(s);
                } else if (op == 2 && !model.isEmpty()) {
                    int s = new ArrayList<>(model.keySet()).get(rnd.nextInt(model.size()));
                    byte[] d = new byte[rnd.nextInt(40)];
                    rnd.nextBytes(d);
                    if (p.update(s, d)) model.put(s, d);
                } else {
                    p = SlottedPage.fromBytes(p.toBytes());
                }
                assertEquals(model.size(), p.liveCount());
                for (Map.Entry<Integer, byte[]> e : model.entrySet()) {
                    assertArrayEquals(e.getValue(), p.get(e.getKey()).orElseThrow());
                }
            }
        }
    }
}
