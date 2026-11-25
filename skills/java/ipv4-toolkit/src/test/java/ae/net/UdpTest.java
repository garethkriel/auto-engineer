package ae.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class UdpTest {
    static final int SRC = 0x0a000001; // 10.0.0.1
    static final int DST = 0x0a000002; // 10.0.0.2
    static final byte[] HELLO = {'h', 'e', 'l', 'l', 'o'};

    @Test
    void encodesLiteralDatagram() {
        // ports 1234 -> 53, length 8+5 = 13, checksum computed independently = 0xa2f8
        assertEquals("04d20035000da2f868656c6c6f", Ipv4PacketTest.toHex(Udp.encode(SRC, DST, 1234, 53, HELLO)));
    }

    @Test
    void decodesLiteralDatagram() {
        Udp.Datagram d = Udp.decode(SRC, DST, Ipv4PacketTest.hex("04d20035000da2f868656c6c6f"));
        assertEquals(1234, d.srcPort());
        assertEquals(53, d.dstPort());
        assertArrayEquals(HELLO, d.payload());
    }

    @Test
    void checksumCoversThePseudoHeaderAddresses() {
        byte[] w = Udp.encode(SRC, DST, 1234, 53, HELLO);
        assertTrue(assertThrows(PacketException.class, () -> Udp.decode(SRC, DST + 1, w)).getMessage()
                .contains("checksum"));
        assertThrows(PacketException.class, () -> Udp.decode(DST, SRC + 1, w));
    }

    @Test
    void zeroChecksumFieldMeansNoChecksum() {
        byte[] w = Ipv4PacketTest.hex("04d20035000d000068656c6c6f");
        assertArrayEquals(HELLO, Udp.decode(SRC, DST, w).payload());
    }

    @Test
    void computedZeroIsTransmittedAsAllOnes() {
        // Find a payload tweak so the real checksum is 0: brute force the last two payload bytes.
        boolean found = false;
        for (int v = 0; v < 0x10000 && !found; v++) {
            byte[] p = {(byte) (v >> 8), (byte) v};
            byte[] w = Udp.encode(SRC, DST, 1, 2, p);
            int field = ((w[6] & 0xff) << 8) | (w[7] & 0xff);
            assertTrue(field != 0, "never emit a zero checksum field, v=" + v);
            // A field of 0xffff must decode (it stands for a computed 0).
            assertEquals(2, Udp.decode(SRC, DST, w).payload().length);
            found = field == 0xffff;
        }
        assertTrue(found, "some 2-byte payload yields a zero sum, so the 0xffff substitution was exercised");
    }

    @Test
    void lengthFieldMustMatchTheByteCount() {
        byte[] w = Udp.encode(SRC, DST, 1, 2, HELLO);
        byte[] longer = Arrays.copyOf(w, w.length + 1);
        assertTrue(assertThrows(PacketException.class, () -> Udp.decode(SRC, DST, longer)).getMessage()
                .contains("length"));
        assertThrows(PacketException.class, () -> Udp.decode(SRC, DST, Arrays.copyOf(w, w.length - 1)));
        assertThrows(PacketException.class, () -> Udp.decode(SRC, DST, Arrays.copyOf(w, 7)));
    }

    @Test
    void oddPayloadLengthRoundTrips() {
        for (int n = 0; n < 12; n++) {
            byte[] p = new byte[n];
            for (int i = 0; i < n; i++) {
                p[i] = (byte) (i * 37 + 1);
            }
            Udp.Datagram d = Udp.decode(SRC, DST, Udp.encode(SRC, DST, 65535, 0, p));
            assertArrayEquals(p, d.payload(), "n=" + n);
            assertEquals(65535, d.srcPort());
            assertEquals(0, d.dstPort());
        }
    }

    @Test
    void anySingleBitFlipInTheSegmentIsDetectedOrChangesFieldsConsistently() {
        byte[] w = Udp.encode(SRC, DST, 1234, 53, HELLO);
        for (int i = 0; i < w.length; i++) {
            if (i == 4 || i == 5) {
                continue; // length flips are covered by the length test
            }
            byte[] c = w.clone();
            c[i] ^= 0x01;
            if (i == 6 || i == 7) {
                // checksum field flipped: either detected, or became 0x0000 ("no checksum") which is accepted
                int field = ((c[6] & 0xff) << 8) | (c[7] & 0xff);
                if (field != 0) {
                    assertThrows(PacketException.class, () -> Udp.decode(SRC, DST, c), "byte " + i);
                }
            } else {
                assertThrows(PacketException.class, () -> Udp.decode(SRC, DST, c), "byte " + i);
            }
        }
    }

    @Test
    void encodeRejectsOversizeAndBadPorts() {
        assertThrows(IllegalArgumentException.class, () -> Udp.encode(SRC, DST, 65536, 1, HELLO));
        assertThrows(IllegalArgumentException.class, () -> Udp.encode(SRC, DST, -1, 1, HELLO));
        assertThrows(IllegalArgumentException.class, () -> Udp.encode(SRC, DST, 1, 1, new byte[65528]));
        assertEquals(65535, Udp.encode(SRC, DST, 1, 1, new byte[65527]).length);
    }

    @Test
    void endToEndThroughIpFragmentation() {
        byte[] payload = new byte[3000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        byte[] seg = Udp.encode(SRC, DST, 5000, 6000, payload);
        Ipv4Packet ip = new Ipv4Packet(0, 77, false, false, 0, 64, 17, SRC, DST, new byte[0], seg);
        Reassembler r = new Reassembler(1000, 4);
        java.util.List<Ipv4Packet> fr = Fragmenter.fragment(ip, 576);
        assertTrue(fr.size() > 5);
        Ipv4Packet out = null;
        for (int i = fr.size() - 1; i >= 0; i--) {
            out = r.accept(Ipv4Packet.decode(fr.get(i).encode()), i).orElse(out);
        }
        assertArrayEquals(payload, Udp.decode(out.src(), out.dst(), out.payload()).payload());
    }
}
