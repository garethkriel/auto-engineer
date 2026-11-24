package ae.chord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The m=6 ring of the Chord paper (figure 4). */
class PaperExampleTest {
    static final long[] IDS = {1, 8, 14, 21, 32, 38, 42, 48, 51, 56};

    static ChordNetwork paperRing() {
        ChordNetwork net = new ChordNetwork(new Ring(6), 3);
        net.create(IDS[0]);
        for (int i = 1; i < IDS.length; i++) {
            net.join(IDS[i], IDS[0]);
            assertTrue(net.stabilize(200) >= 0, "converges after joining " + IDS[i]);
        }
        return net;
    }

    @Test
    void fingerTableOfNode8() {
        ChordNetwork net = paperRing();
        // starts 9,10,12,16,24,40 -> successors 14,14,14,21,32,42
        assertEquals(Arrays.asList(14L, 14L, 14L, 21L, 32L, 42L), net.node(8).fingers());
    }

    @Test
    void fingerTableOfNode56WrapsAround() {
        ChordNetwork net = paperRing();
        // starts 57,58,60,0(=64),8,24 -> 1,1,1,1,8,32
        assertEquals(Arrays.asList(1L, 1L, 1L, 1L, 8L, 32L), net.node(56).fingers());
    }

    @Test
    void lookup54FromNode8FollowsThePaperPath() {
        ChordNetwork net = paperRing();
        Lookup l = net.lookup(8, 54);
        assertEquals(56, l.owner());
        assertEquals(2, l.hops());
        assertEquals(List.of(8L, 42L, 51L), l.path());
    }

    @Test
    void ownerOfOwnIdAndOfPredecessorGap() {
        ChordNetwork net = paperRing();
        assertEquals(14, net.lookup(1, 14).owner());   // key equal to a node id belongs to that node
        assertEquals(1, net.lookup(32, 57).owner());   // wraps past the largest id
        assertEquals(1, net.lookup(32, 0).owner());
        assertEquals(1, net.lookup(1, 1).owner());     // key equals the asking node id
        assertEquals(8, net.lookup(1, 2).owner());
    }

    @Test
    void successorListsAndPredecessors() {
        ChordNetwork net = paperRing();
        assertEquals(List.of(51L, 56L, 1L), net.node(48).successorList());
        assertEquals(List.of(1L, 8L, 14L), net.node(56).successorList());
        assertEquals(8L, net.node(14).predecessor().orElseThrow());
        assertEquals(56L, net.node(1).predecessor().orElseThrow());
        assertEquals(List.of(), Invariants.all(net));
    }
}
