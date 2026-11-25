package ae.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FragmentationTest {
    static byte[] data(int n, int seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static Ipv4Packet pkt(int id, boolean df, boolean mf, int off, byte[] payload) {
        return Ipv4PacketTest.pkt(id, df, mf, off, payload);
    }

    @Test
    void smallPacketIsReturnedUnchanged() {
        Ipv4Packet p = pkt(1, false, false, 0, data(100, 1));
        List<Ipv4Packet> f = Fragmenter.fragment(p, 120);
        assertEquals(1, f.size());
        assertSame(p, f.get(0));
    }

    @Test
    void exactFitIsNotFragmentedAndOneByteMoreIs() {
        Ipv4Packet p = pkt(1, false, false, 0, data(100, 1));
        assertEquals(1, Fragmenter.fragment(p, 120).size());
        assertEquals(2, Fragmenter.fragment(p, 119).size());
    }

    @Test
    void literalSplitOf3000BytesAtMtu1500() {
        // payload per fragment = floor(1480/8)*8 = 1480; 3000 = 1480 + 1480 + 40
        List<Ipv4Packet> f = Fragmenter.fragment(pkt(9, false, false, 0, data(3000, 2)), 1500);
        assertEquals(3, f.size());
        int[] lens = {1480, 1480, 40};
        int[] offs = {0, 185, 370};
        boolean[] mf = {true, true, false};
        for (int i = 0; i < 3; i++) {
            assertEquals(lens[i], f.get(i).payload().length, "len " + i);
            assertEquals(offs[i], f.get(i).fragOffset(), "off " + i);
            assertEquals(mf[i], f.get(i).mf(), "mf " + i);
            assertEquals(9, f.get(i).id());
        }
    }

    @Test
    void nonMultipleOfEightMtuRoundsPayloadDown() {
        // mtu 100 -> 80 payload bytes per fragment (not 80 bytes of 80.. ) : 250 = 80+80+80+10
        List<Ipv4Packet> f = Fragmenter.fragment(pkt(1, false, false, 0, data(250, 3)), 100);
        assertEquals(4, f.size());
        assertEquals(80, f.get(0).payload().length);
        assertEquals(10, f.get(3).payload().length);
        for (Ipv4Packet x : f) {
            assertTrue(x.encode().length <= 100);
        }
        List<Ipv4Packet> g = Fragmenter.fragment(pkt(1, false, false, 0, data(250, 3)), 107);
        assertEquals(80, g.get(0).payload().length, "107-20=87 rounds down to 80");
    }

    @Test
    void dfPacketThatDoesNotFitIsRefused() {
        Ipv4Packet p = pkt(1, true, false, 0, data(200, 4));
        assertThrows(FragmentationNeededException.class, () -> Fragmenter.fragment(p, 100));
        assertEquals(1, Fragmenter.fragment(p, 220).size());
    }

    @Test
    void mtuTooSmallForEightPayloadBytesIsRejected() {
        Ipv4Packet p = pkt(1, false, false, 0, data(50, 5));
        assertThrows(IllegalArgumentException.class, () -> Fragmenter.fragment(p, 27));
        assertEquals(7, Fragmenter.fragment(p, 28).size());
    }

    @Test
    void optionsStayOnFirstFragmentOnly() {
        Ipv4Packet p = new Ipv4Packet(0, 3, false, false, 0, 64, 17, Ipv4PacketTest.A, Ipv4PacketTest.B,
                Ipv4PacketTest.hex("01010101"), data(100, 6));
        List<Ipv4Packet> f = Fragmenter.fragment(p, 64);
        // first: header 24, payload floor(40/8)*8 = 40; others: header 20, payload floor(44/8)*8 = 40
        assertEquals(4, f.get(0).options().length);
        assertEquals(40, f.get(0).payload().length);
        assertEquals(0, f.get(1).options().length);
        assertEquals(40, f.get(1).payload().length);
        assertEquals(5, f.get(1).fragOffset());
        assertEquals(20, f.get(2).payload().length);
    }

    @Test
    void fragmentingAMiddleFragmentKeepsMoreFragmentsAndAddsOffsets() {
        Ipv4Packet mid = pkt(4, false, true, 100, data(160, 7));
        List<Ipv4Packet> f = Fragmenter.fragment(mid, 100);
        assertEquals(2, f.size());
        assertEquals(100, f.get(0).fragOffset());
        assertEquals(110, f.get(1).fragOffset());
        assertTrue(f.get(0).mf());
        assertTrue(f.get(1).mf(), "tail of a non-last fragment still has more fragments after it");
    }

    @Test
    void fragmentingALastFragmentKeepsFinalFlagClear() {
        List<Ipv4Packet> f = Fragmenter.fragment(pkt(4, false, false, 50, data(160, 8)), 100);
        assertTrue(f.get(0).mf());
        assertFalse(f.get(f.size() - 1).mf());
    }

    @Test
    void everyFragmentSurvivesWireRoundTrip() {
        for (Ipv4Packet x : Fragmenter.fragment(pkt(11, false, false, 0, data(777, 9)), 100)) {
            assertEquals(x, Ipv4Packet.decode(x.encode()));
        }
    }

    // ---- round trips ----

    @Test
    void randomizedRoundTripThroughShuffleAndDuplicates() {
        Random r = new Random(2026);
        for (int round = 0; round < 300; round++) {
            int len = r.nextInt(3000);
            int mtu = 28 + r.nextInt(1500);
            byte[] payload = data(len, round);
            Ipv4Packet orig = new Ipv4Packet(r.nextInt(256), r.nextInt(0x10000), false, false, 0, 1 + r.nextInt(255), 17,
                    Ipv4PacketTest.A, Ipv4PacketTest.B, new byte[0], payload);
            List<Ipv4Packet> frags = new ArrayList<>(Fragmenter.fragment(orig, mtu));
            int extra = r.nextInt(3);
            for (int k = 0; k < extra && !frags.isEmpty(); k++) {
                frags.add(frags.get(r.nextInt(frags.size())));
            }
            java.util.Collections.shuffle(frags, r);
            Reassembler re = new Reassembler(1000, 8);
            Ipv4Packet got = null;
            int completions = 0;
            for (int i = 0; i < frags.size(); i++) {
                var out = re.accept(frags.get(i), i);
                if (out.isPresent()) {
                    got = out.get();
                    completions++;
                }
            }
            if (frags.size() == 1 || extra == 0) {
                assertEquals(1, completions, "round " + round + " len " + len + " mtu " + mtu);
            }
            assertTrue(completions >= 1, "round " + round);
            assertEquals(orig, got, "round " + round + " len " + len + " mtu " + mtu);
            assertArrayEquals(payload, got.payload());
        }
    }
}
