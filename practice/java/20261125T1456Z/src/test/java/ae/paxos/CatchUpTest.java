package ae.paxos;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CatchUpTest {
    @Test
    void syncRepliesWithDecidedSlotsFromFirstUndecided() {
        Node a = new Node(0, 3);
        a.receive(new Msg.Decide(1, 0, 0, "a"), 0);
        a.receive(new Msg.Decide(1, 0, 1, "b"), 0);
        a.receive(new Msg.Decide(1, 0, 2, "c"), 0);
        a.receive(new Msg.Sync(2, 0, 1), 1);
        List<Msg> out = a.drainOutbox();
        assertEquals(2, out.size());
        Msg.Decide d1 = assertInstanceOf(Msg.Decide.class, out.get(0));
        assertEquals(1, d1.slot());
        assertEquals(2, d1.to());
        assertEquals("c", assertInstanceOf(Msg.Decide.class, out.get(1)).value());
    }

    @Test
    void followerLearnsMissedDecisionsThroughSyncAfterLeaderCrash() {
        Cluster c = new Cluster(3, 5, 0, 0, 1);
        c.submit(0, "a");
        for (int i = 0; i < 60; i++) c.step();
        c.isolate(2); // node 2 misses what happens next
        c.submit(0, "b");
        for (int i = 0; i < 60; i++) c.step();
        assertEquals(List.of("a", "b"), c.node(0).appliedLog());
        assertEquals(List.of("a"), c.node(2).appliedLog());
        c.crash(0);
        c.heal();
        for (int i = 0; i < 200; i++) { c.step(); c.checkSafety(); }
        assertEquals(List.of("a", "b"), c.node(2).appliedLog());
    }

    @Test
    void followerForwardsCommandToKnownLeader() {
        Cluster c = new Cluster(3, 9, 0, 0, 1);
        c.submit(0, "a");
        for (int i = 0; i < 40; i++) c.step();
        assertEquals(Node.Role.LEADER, c.node(0).role());
        c.submit(1, "via-follower");
        for (int i = 0; i < 60; i++) { c.step(); c.checkSafety(); }
        assertEquals(Node.Role.LEADER, c.node(0).role(), "no new election needed");
        assertEquals(List.of("a", "via-follower"), c.node(2).appliedLog());
    }

    @Test
    void leaderStepsDownOnHigherBallotHeartbeat() {
        Node n = new Node(0, 3);
        n.submit("x", 0);
        for (long t = 0; t < 10 && n.role() == Node.Role.FOLLOWER; t++) n.tick(t);
        Ballot b = assertInstanceOf(Msg.Prepare.class, n.drainOutbox().get(0)).ballot();
        n.receive(new Msg.Promise(1, 0, b, java.util.Map.of()), 11);
        assertEquals(Node.Role.LEADER, n.role());
        n.receive(new Msg.Heartbeat(2, 0, new Ballot(b.round() + 1, 2)), 12);
        assertEquals(Node.Role.FOLLOWER, n.role());
    }

    @Test
    void acceptedForAnOlderBallotIsNotCounted() {
        Node n = new Node(0, 3);
        n.submit("x", 0);
        for (long t = 0; t < 10 && n.role() == Node.Role.FOLLOWER; t++) n.tick(t);
        Ballot b = assertInstanceOf(Msg.Prepare.class, n.drainOutbox().get(0)).ballot();
        n.receive(new Msg.Promise(1, 0, b, java.util.Map.of()), 11);
        n.tick(11);
        n.drainOutbox();
        n.receive(new Msg.Accepted(1, 0, new Ballot(b.round() - 1, 0), 0), 12); // late ack of an old term
        assertTrue(n.decided().isEmpty());
        n.receive(new Msg.Accepted(1, 0, b, 0), 13);
        assertEquals("x", n.decided().get(0));
    }
}
