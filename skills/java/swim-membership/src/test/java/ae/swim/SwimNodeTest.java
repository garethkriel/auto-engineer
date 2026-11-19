package ae.swim;

import static ae.swim.Status.ALIVE;
import static ae.swim.Status.DEAD;
import static ae.swim.Status.SUSPECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Hand-driven protocol tests: we play the network ourselves. */
class SwimNodeTest {
    // period 8, direct timeout 3, 1 indirect probe, suspicion 10, 3 updates per message, retransmit 2*ceil(log2(n+1))
    static final Config CFG = new Config(8, 3, 1, 10, 3, 2);

    static SwimNode node(String id, String... peers) {
        return new SwimNode(id, List.of(peers), CFG, 7L);
    }

    @Test
    void firstTickPingsSomePeerAndAckReplyEchoesSeq() {
        SwimNode a = node("a", "b");
        List<Message> out = a.tick(0);
        assertEquals(1, out.size());
        Ping p = assertInstanceOf(Ping.class, out.get(0));
        assertEquals("a", p.from());
        assertEquals("b", p.to());

        SwimNode b = node("b", "a");
        List<Message> reply = b.receive(p, 1);
        assertEquals(1, reply.size());
        Ack ack = assertInstanceOf(Ack.class, reply.get(0));
        assertEquals(p.seq(), ack.seq());
        assertEquals("b", ack.about());
        assertEquals("a", ack.to());
    }

    @Test
    void ackedProbeLeavesTargetAliveAfterPeriod() {
        SwimNode a = node("a", "b");
        Ping p = assertInstanceOf(Ping.class, a.tick(0).get(0));
        a.receive(new Ack("b", "a", p.seq(), "b", List.of()), 2);
        for (long t = 1; t <= 8; t++) {
            List<Message> out = a.tick(t);
            if (t < 8) {
                assertTrue(out.isEmpty(), "no traffic mid-period at t=" + t);
            }
        }
        assertEquals(ALIVE, a.status("b"));
    }

    @Test
    void unackedProbeEscalatesToPingReqThenSuspect() {
        SwimNode a = node("a", "b", "c");
        Ping p = assertInstanceOf(Ping.class, a.tick(0).get(0));
        String target = p.to();
        String other = target.equals("b") ? "c" : "b";
        for (long t = 1; t < 3; t++) {
            assertTrue(a.tick(t).isEmpty());
        }
        List<Message> out = a.tick(3);
        assertEquals(1, out.size());
        PingReq pr = assertInstanceOf(PingReq.class, out.get(0));
        assertEquals(other, pr.to());
        assertEquals(target, pr.target());
        assertEquals(p.seq(), pr.seq());
        for (long t = 4; t < 8; t++) {
            assertTrue(a.tick(t).isEmpty());
            assertEquals(ALIVE, a.status(target), "still alive at t=" + t);
        }
        a.tick(8); // period over: suspect, and the next probe starts
        assertEquals(SUSPECT, a.status(target));
        assertEquals(0, a.incarnation(target));
    }

    @Test
    void indirectAckThroughRelayClearsTheProbe() {
        SwimNode a = node("a", "b", "c");
        SwimNode c = node("c", "a", "b");
        Ping p = assertInstanceOf(Ping.class, a.tick(0).get(0));
        // Force the probe target to be treated as "b" or "c": make the relay be whichever isn't the target.
        String target = p.to();
        for (long t = 1; t < 3; t++) a.tick(t);
        PingReq pr = assertInstanceOf(PingReq.class, a.tick(3).get(0));
        // The relay node here is named like the other peer; build it explicitly.
        SwimNode relay = node(pr.to(), "a", target);
        List<Message> relayOut = relay.receive(pr, 4);
        Ping relayPing = assertInstanceOf(Ping.class, relayOut.get(0));
        assertEquals(target, relayPing.to());
        assertEquals(pr.to(), relayPing.from());
        SwimNode tgt = node(target, "a", pr.to());
        Ack fromTarget = assertInstanceOf(Ack.class, tgt.receive(relayPing, 5).get(0));
        List<Message> relayed = relay.receive(fromTarget, 6);
        Ack toOrigin = assertInstanceOf(Ack.class, relayed.get(0));
        assertEquals("a", toOrigin.to());
        assertEquals(p.seq(), toOrigin.seq(), "relayed ack carries the origin's seq");
        assertEquals(target, toOrigin.about());
        a.receive(toOrigin, 7);
        a.tick(8);
        assertEquals(ALIVE, a.status(target));
    }

    @Test
    void relayIgnoresAnAckNobodyAskedFor() {
        SwimNode r = node("r", "a", "t");
        assertTrue(r.receive(new Ack("t", "r", 12345, "t", List.of()), 1).isEmpty());
    }

    @Test
    void lateAckAfterSuspicionDoesNotClearSuspicion() {
        SwimNode a = node("a", "b");
        Ping p = assertInstanceOf(Ping.class, a.tick(0).get(0));
        for (long t = 1; t <= 8; t++) a.tick(t);
        assertEquals(SUSPECT, a.status("b"));
        a.receive(new Ack("b", "a", p.seq(), "b", List.of()), 9);
        assertEquals(SUSPECT, a.status("b"), "only a refutation (higher incarnation) clears suspicion");
    }

    @Test
    void suspectBecomesDeadExactlySuspicionTicksLater() {
        SwimNode a = node("a", "b");
        a.tick(0);
        for (long t = 1; t <= 8; t++) a.tick(t); // suspected at t=8
        assertEquals(SUSPECT, a.status("b"));
        for (long t = 9; t < 18; t++) {
            a.tick(t);
            assertEquals(SUSPECT, a.status("b"), "t=" + t);
        }
        a.tick(18);
        assertEquals(DEAD, a.status("b"));
    }

    @Test
    void deadPeersAreNotProbedAgain() {
        SwimNode a = node("a", "b");
        a.receive(new Ping("b", "a", 1, List.of(new Update("b", DEAD, 0))), 0);
        assertEquals(DEAD, a.status("b"));
        for (long t = 0; t < 40; t++) {
            for (Message m : a.tick(t)) {
                assertFalse(m instanceof Ping, "pinged a dead member at t=" + t);
            }
        }
    }

    @Test
    void suspicionAboutSelfIsRefutedWithHigherIncarnation() {
        SwimNode a = node("a", "b");
        List<Message> out = a.receive(new Ping("b", "a", 5, List.of(new Update("a", SUSPECT, 0))), 3);
        assertEquals(1, a.selfIncarnation());
        Ack ack = assertInstanceOf(Ack.class, out.get(0));
        assertTrue(ack.updates().contains(new Update("a", ALIVE, 1)), "ack carries the refutation: " + ack.updates());
    }

    @Test
    void staleSuspicionAboutSelfIsIgnored() {
        SwimNode a = node("a", "b");
        a.receive(new Ping("b", "a", 5, List.of(new Update("a", SUSPECT, 0))), 3); // inc -> 1
        a.receive(new Ping("b", "a", 6, List.of(new Update("a", SUSPECT, 0))), 4); // stale
        assertEquals(1, a.selfIncarnation());
        a.receive(new Ping("b", "a", 7, List.of(new Update("a", SUSPECT, 1))), 5); // current -> 2
        assertEquals(2, a.selfIncarnation());
    }

    @Test
    void nodeToldItIsDeadStopsParticipating() {
        SwimNode a = node("a", "b");
        a.receive(new Ping("b", "a", 5, List.of(new Update("a", DEAD, 0))), 3);
        assertTrue(a.isDeclaredDead());
        assertTrue(a.tick(4).isEmpty());
        assertTrue(a.receive(new Ping("b", "a", 6, List.of()), 5).isEmpty());
    }

    @Test
    void refutationOverridesSuspicionInAnObserver() {
        SwimNode a = node("a", "b");
        a.receive(new Ping("b", "a", 1, List.of(new Update("b", SUSPECT, 0))), 0);
        assertEquals(SUSPECT, a.status("b"));
        a.receive(new Ping("b", "a", 2, List.of(new Update("b", ALIVE, 0))), 1);
        assertEquals(SUSPECT, a.status("b"), "same incarnation does not clear suspicion");
        a.receive(new Ping("b", "a", 3, List.of(new Update("b", ALIVE, 1))), 2);
        assertEquals(ALIVE, a.status("b"));
        assertEquals(1, a.incarnation("b"));
    }

    @Test
    void unknownNodesInUpdatesAreIgnored() {
        SwimNode a = node("a", "b");
        a.receive(new Ping("b", "a", 1, List.of(new Update("zed", DEAD, 0))), 0);
        assertEquals(List.of("b"), List.copyOf(a.view().keySet()));
    }

    @Test
    void gossipIsCappedPerMessageAndPerUpdate() {
        // peers b..f -> n = 6 members -> limit = 2 * ceil(log2 7) = 6 sends per update
        SwimNode a = node("a", "b", "c", "d", "e", "f");
        List<Update> news = List.of(
            new Update("b", SUSPECT, 0), new Update("c", SUSPECT, 0), new Update("d", SUSPECT, 0),
            new Update("e", SUSPECT, 0), new Update("f", SUSPECT, 0));
        a.receive(new Ping("b", "a", 1, news), 0);
        assertEquals(5, a.pendingGossip());
        int sentTotal = 0;
        for (int i = 0; i < 40; i++) {
            Ack ack = assertInstanceOf(Ack.class, a.receive(new Ping("b", "a", 100 + i, List.of()), 1).get(0));
            // payload: <=3 gossip updates, plus at most one targeted update about the receiver (b)
            long nonTargeted = ack.updates().stream().filter(u -> !u.node().equals("b")).count();
            assertTrue(nonTargeted <= 3, "cap violated: " + ack.updates());
            sentTotal += ack.updates().size();
        }
        assertEquals(0, a.pendingGossip(), "every update is retired after its retransmit limit");
        // 5 updates * 6 sends each = 30 piggybacked slots, plus 40 targeted copies of the suspicion about b
        // (one of the five gossip slots about b is itself sent 6 times, deduplicated against the targeted copy).
        assertTrue(sentTotal >= 30, "sent " + sentTotal);
    }

    @Test
    void messageToAMemberCarriesWhatWeSuspectAboutIt() {
        SwimNode a = node("a", "b", "c");
        a.receive(new Ping("c", "a", 1, List.of(new Update("b", SUSPECT, 3))), 0);
        // drain gossip so only the targeted rule can mention b
        for (int i = 0; i < 40; i++) a.receive(new Ping("c", "a", 50 + i, List.of()), 1);
        assertEquals(0, a.pendingGossip());
        Ack toB = assertInstanceOf(Ack.class, a.receive(new Ping("b", "a", 99, List.of()), 2).get(0));
        assertTrue(toB.updates().contains(new Update("b", SUSPECT, 3)), toB.updates().toString());
        Ack toC = assertInstanceOf(Ack.class, a.receive(new Ping("c", "a", 98, List.of()), 2).get(0));
        assertTrue(toC.updates().isEmpty(), toC.updates().toString());
    }

    @Test
    void messagesForSomeoneElseAreRejected() {
        SwimNode a = node("a", "b");
        assertThrows(IllegalArgumentException.class, () -> a.receive(new Ping("b", "z", 1, List.of()), 0));
    }

    @Test
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> node("a", "a"));
        assertThrows(IllegalArgumentException.class, () -> node("a", "b", "b"));
    }
}
