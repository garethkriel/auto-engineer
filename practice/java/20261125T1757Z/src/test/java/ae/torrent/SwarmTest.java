package ae.torrent;

import static ae.torrent.PiecePickerTest.bits;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SwarmTest {
    /** 10 pieces of 8 bytes (80 bytes). */
    static final byte[] DATA = TorrentTest.data(80, 42);
    static final Torrent T = Torrent.create("blob", DATA, 8);

    static Swarm swarm(int up, int down) {
        return new Swarm(T, DATA, up, down);
    }

    @Test
    void honestSwarmCompletesWithIdenticalDataAndRespectsSlots() {
        Swarm s = swarm(2, 2);
        s.addSeed("seed");
        for (int i = 1; i <= 4; i++) {
            s.addLeecher("l" + i);
        }
        SwarmResult r = s.run(500);
        assertTrue(r.complete());
        assertFalse(r.stalled());
        assertEquals(0, r.hashFailures());
        assertEquals(Set.of(), r.bans());
        assertTrue(r.maxServedPerTick() <= 2, "served " + r.maxServedPerTick());
        assertEquals(2, r.maxServedPerTick(), "the seed is saturated at some point");
        for (int i = 1; i <= 4; i++) {
            assertArrayEquals(DATA, s.content("l" + i));
            assertTrue(r.completionTick().get("l" + i) <= r.ticks());
        }
        assertEquals(4, r.completionTick().size());
    }

    @Test
    void tickCountIsBoundedByTheTighterOfUploadAndDownloadSlots() {
        // one seed, one leecher, 10 pieces: per tick min(up, down) pieces
        int[][] table = {{1, 1, 10}, {3, 1, 10}, {3, 5, 4}, {5, 2, 5}, {100, 100, 1}, {4, 4, 3}};
        for (int[] row : table) {
            Swarm s = swarm(row[0], row[1]);
            s.addSeed("s");
            s.addLeecher("l");
            SwarmResult r = s.run(100);
            assertTrue(r.complete());
            assertEquals(row[2], r.ticks(), "up=" + row[0] + " down=" + row[1]);
            assertEquals(row[2], r.completionTick().get("l"));
        }
    }

    @Test
    void leecherTakesRarestPieceFirst() {
        // L is added first so it moves first each tick; P1 and P2 are partial peers, nobody has 3 or 4.
        // availability seen by L: piece0=2, piece1=1, piece2=1.
        // tick1: L picks 1 (rarest, lowest index of {1,2}). Then P1 takes 2 from P2, P2 takes 1 from P1.
        // tick2: availability is now 0:2, 1:2, 2:2 -> L picks 0 (tie, lowest). tick3: L picks 2.
        Swarm s = swarm(100, 1);
        s.addLeecher("L");
        s.addPartial("P1", bits(0, 1));
        s.addPartial("P2", bits(0, 2));
        s.step();
        s.step();
        s.step();
        assertEquals(List.of(1, 0, 2), s.history("L"));
        assertFalse(s.isComplete("L"));
        assertFalse(s.step(), "pieces 3 and 4.. exist nowhere, so nothing can happen");
    }

    @Test
    void poisonerFirstThenHonestSeedCostsExactlyTwoFailuresAndOneBan() {
        // tick1: piece0 from P (first added) is corrupted -> strike 1; tick2: again -> strike 2, ban.
        // tick3..12: ten honest pieces from the seed at one per tick (download slot = 1).
        Swarm s = swarm(5, 1);
        s.addPoisoner("P");
        s.addSeed("S");
        s.addLeecher("L");
        SwarmResult r = s.run(100);
        assertTrue(r.complete());
        assertEquals(2, r.hashFailures());
        assertEquals(Set.of("L>P"), r.bans());
        assertEquals(12, r.ticks());
        assertArrayEquals(DATA, s.content("L"));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), s.history("L"));
        assertTrue(s.isBanned("L", "P"));
        assertFalse(s.isBanned("L", "S"));
    }

    @Test
    void oneStrikeIsNotEnoughToBan() {
        // Poisoner is the only uploader for the first tick only: it departs-equivalent via the seed taking over
        // is not possible, so verify the strike threshold directly: after tick 1 there is a failure but no ban.
        Swarm s = swarm(5, 1);
        s.addPoisoner("P");
        s.addSeed("S");
        s.addLeecher("L");
        assertTrue(s.step());
        assertFalse(s.isBanned("L", "P"));
        assertEquals(0, s.history("L").size());
        assertTrue(s.step());
        assertTrue(s.isBanned("L", "P"));
    }

    @Test
    void poisonerAloneStallsAndTerminates() {
        Swarm s = swarm(5, 1);
        s.addPoisoner("P");
        s.addLeecher("L");
        SwarmResult r = s.run(1000);
        assertFalse(r.complete());
        assertTrue(r.stalled());
        assertEquals(2, r.hashFailures());
        assertEquals(Set.of("L>P"), r.bans());
        assertEquals(3, r.ticks()); // strike, strike+ban, stalled tick
        assertEquals(0, s.have("L").cardinality());
        assertThrows(IllegalStateException.class, () -> s.content("L"));
    }

    @Test
    void honestPeersAreNeverBannedInAMixedSwarm() {
        Swarm s = swarm(2, 2);
        s.addPoisoner("P1");
        s.addSeed("S", 8);
        s.addPoisoner("P2");
        for (int i = 0; i < 5; i++) {
            s.addLeecher("l" + i);
        }
        SwarmResult r = s.run(500);
        assertTrue(r.complete());
        assertTrue(r.hashFailures() >= 2);
        for (String ban : r.bans()) {
            String uploader = ban.substring(ban.indexOf('>') + 1);
            assertTrue(uploader.startsWith("P"), ban);
        }
        for (int i = 0; i < 5; i++) {
            assertArrayEquals(DATA, s.content("l" + i));
        }
    }

    @Test
    void seedLeavingTooEarlyLeavesMissingPiecesForever() {
        // derived in the design notes: after ticks 1-2 A={0,1,4,5} B={2,3,6,7}; pieces 8,9 live only on the seed.
        // seed leaves at tick 3; A and B swap for two ticks and both end with exactly {0..7}; tick 5 is a stall.
        Swarm s = swarm(100, 2);
        s.addSeed("S", 3);
        s.addLeecher("A");
        s.addLeecher("B");
        SwarmResult r = s.run(100);
        assertFalse(r.complete());
        assertTrue(r.stalled());
        assertEquals(5, r.ticks());
        assertEquals(bits(0, 1, 2, 3, 4, 5, 6, 7), s.have("A"));
        assertEquals(bits(0, 1, 2, 3, 4, 5, 6, 7), s.have("B"));
        assertFalse(s.isLive("S"));
    }

    @Test
    void sameSwarmWithTheSeedStayingLongerCompletes() {
        Swarm s = swarm(100, 2);
        s.addSeed("S", 5);
        s.addLeecher("A");
        s.addLeecher("B");
        SwarmResult r = s.run(100);
        assertTrue(r.complete());
        assertArrayEquals(DATA, s.content("A"));
        assertArrayEquals(DATA, s.content("B"));
    }

    @Test
    void pickerAvailabilityMatchesOracleAfterEveryTick() {
        Swarm s = swarm(2, 2);
        s.addPoisoner("P");
        s.addSeed("S", 7);
        s.addSeed("S2", 12);
        s.addPartial("X", bits(1, 4, 7));
        for (int i = 0; i < 3; i++) {
            s.addLeecher("l" + i);
        }
        List<String> leechers = List.of("X", "l0", "l1", "l2");
        List<String> everyone = List.of("P", "S", "S2", "X", "l0", "l1", "l2");
        for (int tick = 0; tick < 60; tick++) {
            boolean active = s.step();
            for (String l : leechers) {
                for (int piece = 0; piece < T.pieceCount(); piece++) {
                    int expected = 0;
                    for (String q : everyone) {
                        if (!q.equals(l) && s.isLive(q) && !s.isBanned(l, q) && s.have(q).get(piece)) {
                            expected++;
                        }
                    }
                    assertEquals(expected, s.availability(l, piece), "tick " + s.tick() + " " + l + " piece " + piece);
                }
            }
            if (!active) {
                break;
            }
        }
        assertTrue(s.isComplete("l0"));
        assertTrue(s.isComplete("l1"));
        assertTrue(s.isComplete("l2"));
        assertTrue(s.isComplete("X"));
    }

    @Test
    void runsAreDeterministic() {
        SwarmResult[] rs = new SwarmResult[2];
        for (int k = 0; k < 2; k++) {
            Swarm s = swarm(2, 3);
            s.addPoisoner("P");
            s.addSeed("S", 9);
            for (int i = 0; i < 4; i++) {
                s.addLeecher("l" + i);
            }
            rs[k] = s.run(200);
        }
        assertEquals(rs[0], rs[1]);
    }

    @Test
    void historyNeverRepeatsAPieceAndCoversExactlyTheMissingOnes() {
        Swarm s = swarm(3, 2);
        s.addSeed("S");
        s.addPartial("X", bits(0, 3, 9));
        SwarmResult r = s.run(100);
        assertTrue(r.complete());
        List<Integer> h = new ArrayList<>(s.history("X"));
        assertEquals(7, h.size()); // 10 pieces - 3 owned
        assertEquals(7, Set.copyOf(h).size());
        BitSet got = new BitSet();
        h.forEach(got::set);
        assertEquals(bits(1, 2, 4, 5, 6, 7, 8), got);
        assertArrayEquals(DATA, s.content("X"));
    }

    @Test
    void argumentValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Swarm(T, DATA, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Swarm(T, DATA, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Swarm(T, new byte[79], 1, 1));
        byte[] wrong = DATA.clone();
        wrong[5] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> new Swarm(T, wrong, 1, 1));
        Swarm s = swarm(1, 1);
        s.addSeed("a");
        assertThrows(IllegalArgumentException.class, () -> s.addLeecher("a"));
        assertThrows(IllegalArgumentException.class, () -> s.addPartial("b", bits(10)));
        assertThrows(IllegalArgumentException.class, () -> s.addSeed("c", 0));
        assertThrows(IllegalArgumentException.class, () -> s.have("nobody"));
        assertThrows(IllegalArgumentException.class, () -> s.availability("a", 0), "seeds have no picker");
    }
}
