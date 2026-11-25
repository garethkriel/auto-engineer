package ae.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class Ipv4PacketTest {
    static final int A = 0xc0a80001; // 192.168.0.1
    static final int B = 0xc0a800c7; // 192.168.0.199

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    static Ipv4Packet pkt(int id, boolean df, boolean mf, int off, byte[] payload) {
        return new Ipv4Packet(0, id, df, mf, off, 64, 17, A, B, new byte[0], payload);
    }

    @Test
    void encodesWikipediaHeaderExactly() {
        Ipv4Packet p = pkt(0, true, false, 0, new byte[95]);
        byte[] w = p.encode();
        assertEquals(115, w.length);
        assertEquals("450000730000400040" + "11" + "b861" + "c0a80001c0a800c7", toHex(Arrays.copyOf(w, 20)));
    }

    @Test
    void flagsAndOffsetFieldLayout() {
        // id 0x1234, mf, offset 0x0abc -> flags/frag word = 0x2000 | 0x0abc = 0x2abc
        Ipv4Packet p = pkt(0x1234, false, true, 0x0abc, new byte[8]);
        byte[] w = p.encode();
        assertEquals("1234", toHex(Arrays.copyOfRange(w, 4, 6)));
        assertEquals("2abc", toHex(Arrays.copyOfRange(w, 6, 8)));
    }

    @Test
    void optionsGrowIhl() {
        Ipv4Packet p = new Ipv4Packet(0, 1, false, false, 0, 64, 6, A, B, hex("01010101"), new byte[]{9});
        byte[] w = p.encode();
        assertEquals(0x46, w[0] & 0xff);
        assertEquals(25, w.length);
        assertEquals("01010101", toHex(Arrays.copyOfRange(w, 20, 24)));
        assertEquals(p, Ipv4Packet.decode(w));
    }

    @Test
    void roundTripPreservesEveryField() {
        Ipv4Packet p = new Ipv4Packet(0xb8, 0xffff, false, true, 8191, 1, 255, 0xffffffff, 0, hex("0000000000000000"),
                new byte[] {1, 2, 3});
        Ipv4Packet q = Ipv4Packet.decode(p.encode());
        assertEquals(p, q);
        assertEquals(p.hashCode(), q.hashCode());
        assertEquals(0xffffffff, q.src());
        assertEquals(8191, q.fragOffset());
    }

    @Test
    void equalityComparesArrayContents() {
        assertEquals(pkt(1, false, false, 0, new byte[]{1}), pkt(1, false, false, 0, new byte[]{1}));
        assertNotEquals(pkt(1, false, false, 0, new byte[]{1}), pkt(1, false, false, 0, new byte[]{2}));
        assertNotEquals(pkt(1, false, false, 0, new byte[]{1}), pkt(2, false, false, 0, new byte[]{1}));
    }

    @Test
    void payloadIsDefensivelyCopied() {
        byte[] data = {1, 2, 3};
        Ipv4Packet p = pkt(1, false, false, 0, data);
        data[0] = 99;
        assertEquals(1, p.payload()[0]);
        p.payload()[1] = 77;
        assertEquals(2, p.payload()[1]);
    }

    @Test
    void trailingLinkLayerPaddingIsIgnored() {
        Ipv4Packet p = pkt(5, false, false, 0, new byte[]{1, 2});
        byte[] w = Arrays.copyOf(p.encode(), 60);
        assertEquals(p, Ipv4Packet.decode(w));
    }

    @Test
    void oversizeTotalLengthIsRejectedOnEncode() {
        assertThrows(IllegalArgumentException.class, () -> pkt(1, false, false, 0, new byte[65516]).encode());
        assertEquals(65535, pkt(1, false, false, 0, new byte[65515]).encode().length);
    }

    @Test
    void constructorValidatesRanges() {
        assertThrows(IllegalArgumentException.class, () -> pkt(0x10000, false, false, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> pkt(-1, false, false, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> pkt(1, false, false, 8192, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Ipv4Packet(0, 1, false, false, 0, 256, 1, A, B,
                new byte[0], new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Ipv4Packet(0, 1, false, false, 0, 1, 1, A, B,
                new byte[3], new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Ipv4Packet(0, 1, false, false, 0, 1, 1, A, B,
                new byte[44], new byte[0]));
    }

    // ---- strict decoding: each rejection has a control that differs by exactly one defect ----

    private static byte[] valid() {
        return pkt(7, false, false, 0, new byte[]{1, 2, 3, 4}).encode();
    }

    /** Recomputes the header checksum so a crafted defect cannot be rejected for a bad checksum. */
    private static byte[] reseal(byte[] w) {
        int ihl = (w[0] & 0x0f) * 4;
        w[10] = 0;
        w[11] = 0;
        int c = Checksum.of(w, 0, ihl);
        w[10] = (byte) (c >> 8);
        w[11] = (byte) c;
        return w;
    }

    private static void assertRejected(byte[] w, String fragment) {
        PacketException e = assertThrows(PacketException.class, () -> Ipv4Packet.decode(w));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    @Test
    void controlDecodes() {
        assertEquals(4, Ipv4Packet.decode(reseal(valid())).payload().length);
    }

    @Test
    void rejectsShortInput() {
        assertRejected(Arrays.copyOf(valid(), 19), "short");
    }

    @Test
    void rejectsWrongVersion() {
        byte[] w = valid();
        w[0] = 0x65;
        assertRejected(reseal(w), "version");
    }

    @Test
    void rejectsIhlBelowFive() {
        byte[] w = valid();
        w[0] = 0x44;
        assertRejected(reseal(w), "ihl");
    }

    @Test
    void rejectsHeaderLongerThanData() {
        byte[] w = Arrays.copyOf(valid(), 22);
        w[0] = 0x4f; // ihl 15 -> 60 bytes; checked before the checksum, so no reseal (it would read past the array)
        assertRejected(w, "header");
    }

    @Test
    void rejectsTotalLengthBelowHeader() {
        byte[] w = valid();
        w[3] = 19;
        assertRejected(reseal(w), "total length");
    }

    @Test
    void rejectsTotalLengthBeyondData() {
        byte[] w = valid();
        w[3] = (byte) (w[3] + 1);
        assertRejected(reseal(w), "total length");
    }

    @Test
    void rejectsBadChecksum() {
        byte[] w = valid();
        w[10] ^= 1;
        assertRejected(w, "checksum");
    }

    @Test
    void rejectsReservedFlag() {
        byte[] w = valid();
        w[6] |= (byte) 0x80;
        assertRejected(reseal(w), "reserved");
    }

    @Test
    void rejectsDfWithMoreFragments() {
        byte[] w = valid();
        w[6] = 0x60;
        assertRejected(reseal(w), "inconsistent");
    }

    @Test
    void rejectsDfWithNonZeroOffset() {
        byte[] w = valid();
        w[6] = 0x40;
        w[7] = 1;
        assertRejected(reseal(w), "inconsistent");
    }

    @Test
    void everyTruncationOfAValidPacketIsRejectedOrNeverCrashes() {
        byte[] w = valid();
        for (int n = 0; n < w.length; n++) {
            byte[] cut = Arrays.copyOf(w, n);
            assertThrows(PacketException.class, () -> Ipv4Packet.decode(cut), "len " + n);
        }
    }

    @Test
    void flippingAnyByteGivesExceptionOrDifferentPacketNeverAnotherException() {
        byte[] w = pkt(7, false, false, 0, new byte[]{1, 2, 3, 4}).encode();
        Ipv4Packet orig = Ipv4Packet.decode(w);
        for (int i = 0; i < w.length; i++) {
            byte[] c = w.clone();
            c[i] ^= 0x10;
            try {
                Ipv4Packet q = Ipv4Packet.decode(c);
                assertNotEquals(orig, q, "byte " + i);
                if (i >= 20) {
                    int diff = 0;
                    for (int k = 0; k < 4; k++) {
                        if (q.payload()[k] != orig.payload()[k]) {
                            diff++;
                        }
                    }
                    assertEquals(1, diff, "exactly one payload byte differs");
                }
            } catch (PacketException expected) {
                assertTrue(i < 20, "payload flip must still decode, byte " + i);
            }
        }
    }
}
