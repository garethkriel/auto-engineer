package ae.torrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PiecePickerTest {
    static BitSet bits(int... ones) {
        BitSet b = new BitSet();
        for (int i : ones) {
            b.set(i);
        }
        return b;
    }

    @Test
    void picksRarestAndBreaksTiesByLowestIndex() {
        PiecePicker p = new PiecePicker(5);
        // availability: piece0=3, 1=1, 2=2, 3=1, 4=3
        p.addPeer("a", bits(0, 2, 4));
        p.addPeer("b", bits(0, 1, 2, 4));
        p.addPeer("c", bits(0, 3, 4));
        assertEquals(3, p.availability(0));
        assertEquals(1, p.availability(1));
        assertEquals(2, p.availability(2));
        assertEquals(1, p.availability(3));
        assertEquals(3, p.availability(4));
        assertEquals(1, p.pick(bits(0, 1, 2, 3, 4)), "1 and 3 tie at 1 -> lowest index");
        assertEquals(3, p.pick(bits(0, 2, 3, 4)));
        assertEquals(2, p.pick(bits(0, 2, 4)));
        assertEquals(0, p.pick(bits(0, 4)), "tie at 3 -> lowest");
        assertEquals(-1, p.pick(new BitSet()));
    }

    @Test
    void pickDoesNotModifyItsArgumentAndIsRepeatable() {
        PiecePicker p = new PiecePicker(3);
        p.addPeer("a", bits(0, 1, 2));
        BitSet c = bits(1, 2);
        assertEquals(1, p.pick(c));
        assertEquals(bits(1, 2), c);
        assertEquals(1, p.pick(c));
    }

    @Test
    void haveRemoveAndReplaceUpdateAvailability() {
        PiecePicker p = new PiecePicker(4);
        p.addPeer("a", bits(0));
        p.addPeer("b", bits(0, 1));
        assertEquals(2, p.peerCount());
        p.peerHave("a", 2);
        assertEquals(1, p.availability(2));
        p.peerHave("a", 2); // idempotent
        assertEquals(1, p.availability(2));
        p.addPeer("a", bits(3)); // replaces: loses 0 and 2, gains 3
        assertEquals(1, p.availability(0));
        assertEquals(0, p.availability(2));
        assertEquals(1, p.availability(3));
        assertEquals(2, p.peerCount());
        p.removePeer("b");
        assertEquals(0, p.availability(0));
        assertEquals(0, p.availability(1));
        assertEquals(1, p.peerCount());
        p.removePeer("zzz"); // unknown: no-op
        assertEquals(1, p.peerCount());
    }

    @Test
    void argumentValidation() {
        PiecePicker p = new PiecePicker(3);
        p.addPeer("a", bits(0));
        assertThrows(IllegalArgumentException.class, () -> p.peerHave("nobody", 0));
        assertThrows(IllegalArgumentException.class, () -> p.peerHave("a", 3));
        assertThrows(IllegalArgumentException.class, () -> p.peerHave("a", -1));
        assertThrows(IllegalArgumentException.class, () -> p.addPeer("b", bits(3)));
        assertThrows(IllegalArgumentException.class, () -> p.pick(bits(3)));
        assertThrows(IllegalArgumentException.class, () -> p.availability(3));
        assertThrows(IllegalArgumentException.class, () -> new PiecePicker(-1));
        assertEquals(0, new PiecePicker(0).pick(new BitSet()) + 1, "zero pieces: nothing to pick");
    }

    @Test
    void addPeerCopiesTheBitfield() {
        PiecePicker p = new PiecePicker(3);
        BitSet mine = bits(0);
        p.addPeer("a", mine);
        mine.set(1);
        assertEquals(0, p.availability(1));
    }

    @Test
    void availabilityMatchesOracleAfterRandomOperations() {
        Random r = new Random(5);
        int n = 12;
        PiecePicker p = new PiecePicker(n);
        Map<String, BitSet> model = new HashMap<>();
        for (int step = 0; step < 4000; step++) {
            String id = "p" + r.nextInt(6);
            switch (r.nextInt(3)) {
                case 0: {
                    BitSet b = new BitSet();
                    for (int i = 0; i < n; i++) {
                        if (r.nextBoolean()) {
                            b.set(i);
                        }
                    }
                    p.addPeer(id, b);
                    model.put(id, (BitSet) b.clone());
                    break;
                }
                case 1: {
                    int piece = r.nextInt(n);
                    if (model.containsKey(id)) {
                        p.peerHave(id, piece);
                        model.get(id).set(piece);
                    } else {
                        assertThrows(IllegalArgumentException.class, () -> p.peerHave(id, piece));
                    }
                    break;
                }
                default:
                    p.removePeer(id);
                    model.remove(id);
            }
            assertEquals(model.size(), p.peerCount());
            BitSet cand = new BitSet();
            for (int i = 0; i < n; i++) {
                int expected = 0;
                for (BitSet b : model.values()) {
                    if (b.get(i)) {
                        expected++;
                    }
                }
                assertEquals(expected, p.availability(i), "step " + step + " piece " + i);
                if (r.nextInt(3) > 0) {
                    cand.set(i);
                }
            }
            // oracle pick: minimum availability, lowest index
            int best = -1;
            for (int i = cand.nextSetBit(0); i >= 0; i = cand.nextSetBit(i + 1)) {
                if (best < 0 || p.availability(i) < p.availability(best)) {
                    best = i;
                }
            }
            assertEquals(best, p.pick(cand));
        }
    }
}
