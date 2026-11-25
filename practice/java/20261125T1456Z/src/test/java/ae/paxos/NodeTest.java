package ae.paxos;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeTest {
    private static Msg.Promise onlyPromise(List<Msg> l) {
        assertEquals(1, l.size());
        return assertInstanceOf(Msg.Promise.class, l.get(0));
    }

    @Test
    void ballotOrdersByRoundThenNode() {
        assertTrue(new Ballot(1, 2).compareTo(new Ballot(2, 0)) < 0);
        assertTrue(new Ballot(2, 1).compareTo(new Ballot(2, 0)) > 0);
        assertTrue(Ballot.NONE.compareTo(new Ballot(1, 0)) < 0);
        assertEquals(0, new Ballot(3, 1).compareTo(new Ballot(3, 1)));
    }

    @Test
    void preparePromisesAndLowerPrepareIsNacked() {
        Node a = new Node(0, 3);
        a.receive(new Msg.Prepare(1, 0, new Ballot(5, 1)), 0);
        Msg.Promise p = onlyPromise(a.drainOutbox());
        assertEquals(new Ballot(5, 1), p.ballot());
        assertEquals(1, p.to());
        a.receive(new Msg.Prepare(2, 0, new Ballot(4, 2)), 1);
        Msg.Nack nack = assertInstanceOf(Msg.Nack.class, a.drainOutbox().get(0));
        assertEquals(new Ballot(5, 1), nack.promised());
        assertEquals(2, nack.to());
    }

    @Test
    void equalBallotPrepareIsAcceptedAgain() {
        Node a = new Node(0, 3);
        a.receive(new Msg.Prepare(1, 0, new Ballot(5, 1)), 0);
        a.drainOutbox();
        a.receive(new Msg.Prepare(1, 0, new Ballot(5, 1)), 1);
        assertInstanceOf(Msg.Promise.class, a.drainOutbox().get(0));
    }

    @Test
    void acceptBelowPromiseIsRejectedAndLeavesStateUnchanged() {
        Node a = new Node(0, 3);
        a.receive(new Msg.Prepare(1, 0, new Ballot(5, 1)), 0);
        a.drainOutbox();
        a.receive(new Msg.Accept(2, 0, new Ballot(4, 2), 0, "x"), 1);
        assertInstanceOf(Msg.Nack.class, a.drainOutbox().get(0));
        assertTrue(a.accepted().isEmpty());
        assertEquals(new Ballot(5, 1), a.promised());
    }

    @Test
    void acceptAtOrAboveRaisesPromiseAndPromiseReportsIt() {
        Node a = new Node(0, 3);
        a.receive(new Msg.Accept(1, 0, new Ballot(3, 1), 0, "x"), 0);
        Msg.Accepted ack = assertInstanceOf(Msg.Accepted.class, a.drainOutbox().get(0));
        assertEquals(0, ack.slot());
        assertEquals(new Ballot(3, 1), a.promised());
        a.receive(new Msg.Prepare(2, 0, new Ballot(9, 2)), 1);
        Msg.Promise p = onlyPromise(a.drainOutbox());
        assertEquals(Map.of(0, new Node.Accepted(new Ballot(3, 1), "x")), p.accepted());
    }

    @Test
    void promiseOutputIsADefensiveCopy() {
        Node a = new Node(0, 3);
        a.receive(new Msg.Accept(1, 0, new Ballot(3, 1), 0, "x"), 0);
        a.drainOutbox();
        a.receive(new Msg.Prepare(2, 0, new Ballot(9, 2)), 1);
        Msg.Promise p = onlyPromise(a.drainOutbox());
        a.receive(new Msg.Accept(2, 0, new Ballot(9, 2), 0, "y"), 2);
        assertEquals("x", p.accepted().get(0).value());
    }

    @Test
    void conflictingDecideThrows() {
        Node a = new Node(0, 3);
        a.receive(new Msg.Decide(1, 0, 0, "x"), 0);
        a.receive(new Msg.Decide(1, 0, 0, "x"), 1); // duplicate is fine
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> a.receive(new Msg.Decide(2, 0, 0, "y"), 2));
        assertTrue(e.getMessage().startsWith("conflicting decision"));
    }

    @Test
    void appliedLogSkipsNoopsGapsAndDuplicates() {
        Node a = new Node(0, 3);
        a.receive(new Msg.Decide(1, 0, 0, "a"), 0);
        a.receive(new Msg.Decide(1, 0, 1, Node.NOOP), 0);
        a.receive(new Msg.Decide(1, 0, 3, "c"), 0); // gap at 2
        assertEquals(List.of("a"), a.appliedLog());
        a.receive(new Msg.Decide(1, 0, 2, "a"), 0); // repeated command
        assertEquals(List.of("a", "c"), a.appliedLog());
    }

    /** Candidate 0 must adopt the highest-ballot accepted value for a slot, not its own command. */
    @Test
    void newLeaderAdoptsHighestBallotValueAndFillsGapsWithNoop() {
        Node n = new Node(0, 3);
        n.submit("mine", 0);
        for (long t = 0; t < 10 && n.role() == Node.Role.FOLLOWER; t++) n.tick(t);
        assertEquals(Node.Role.CANDIDATE, n.role());
        List<Msg> out = n.drainOutbox();
        Ballot b = assertInstanceOf(Msg.Prepare.class, out.get(0)).ballot();
        assertEquals(2, out.size());
        // peer 1 accepted slot 1 = "old" at ballot (1,2); peer 2 (same slot) = "new" at ballot (2,1)
        n.receive(new Msg.Promise(1, 0, b, Map.of(1, new Node.Accepted(new Ballot(1, 2), "old"))), 20);
        assertEquals(Node.Role.LEADER, n.role());
        List<Msg> acc = n.drainOutbox().stream().filter(m -> m instanceof Msg.Accept).toList();
        // slot 0 is a gap -> NOOP, slot 1 -> "old", slot 2 -> "mine"; each sent to 2 peers
        assertEquals(6, acc.size());
        assertEquals(Node.NOOP, valueFor(acc, 0));
        assertEquals("old", valueFor(acc, 1));
        assertEquals("mine", valueFor(acc, 2));
    }

    @Test
    void highestBallotWinsAmongPromises() {
        Node n = new Node(0, 5);
        n.submit("mine", 0);
        for (long t = 0; t < 10 && n.role() == Node.Role.FOLLOWER; t++) n.tick(t);
        Ballot b = assertInstanceOf(Msg.Prepare.class, n.drainOutbox().get(0)).ballot();
        n.receive(new Msg.Promise(1, 0, b, Map.of(0, new Node.Accepted(new Ballot(1, 1), "low"))), 20);
        assertEquals(Node.Role.CANDIDATE, n.role());
        n.receive(new Msg.Promise(2, 0, b, Map.of(0, new Node.Accepted(new Ballot(3, 2), "high"))), 20);
        assertEquals(Node.Role.LEADER, n.role());
        List<Msg> acc = n.drainOutbox().stream().filter(m -> m instanceof Msg.Accept).toList();
        assertEquals("high", valueFor(acc, 0));
    }

    @Test
    void promiseForOldBallotOrFromSameNodeTwiceDoesNotCountTwice() {
        Node n = new Node(0, 5);
        n.submit("mine", 0);
        for (long t = 0; t < 10 && n.role() == Node.Role.FOLLOWER; t++) n.tick(t);
        Ballot b = assertInstanceOf(Msg.Prepare.class, n.drainOutbox().get(0)).ballot();
        n.receive(new Msg.Promise(1, 0, b, Map.of()), 20);
        n.receive(new Msg.Promise(1, 0, b, Map.of()), 20); // duplicate: self+1 = 2 of 3 needed
        assertEquals(Node.Role.CANDIDATE, n.role());
        n.receive(new Msg.Promise(2, 0, new Ballot(b.round() - 1, 0), Map.of()), 20); // stale ballot
        assertEquals(Node.Role.CANDIDATE, n.role());
        n.receive(new Msg.Promise(3, 0, b, Map.of()), 20);
        assertEquals(Node.Role.LEADER, n.role());
    }

    @Test
    void nackStepsLeaderDownAndNextBallotIsHigher() {
        Node n = new Node(0, 3);
        n.submit("mine", 0);
        for (long t = 0; t < 10 && n.role() == Node.Role.FOLLOWER; t++) n.tick(t);
        Ballot b = assertInstanceOf(Msg.Prepare.class, n.drainOutbox().get(0)).ballot();
        n.receive(new Msg.Nack(1, 0, new Ballot(7, 1)), 5);
        assertEquals(Node.Role.FOLLOWER, n.role());
        long t = 6;
        while (n.role() == Node.Role.FOLLOWER && t < 60) n.tick(t++);
        Ballot b2 = assertInstanceOf(Msg.Prepare.class, n.drainOutbox().get(0)).ballot();
        assertTrue(b2.compareTo(new Ballot(7, 1)) > 0, b + " -> " + b2);
    }

    @Test
    void crashKeepsDurableStateAndClearsVolatile() {
        Node a = new Node(0, 3);
        a.receive(new Msg.Accept(1, 0, new Ballot(3, 1), 0, "x"), 0);
        a.receive(new Msg.Decide(1, 0, 5, "d"), 0);
        a.submit("pending", 0);
        a.crash();
        assertEquals(new Ballot(3, 1), a.promised());
        assertEquals("x", a.accepted().get(0).value());
        assertEquals("d", a.decided().get(5));
        assertEquals(Node.Role.FOLLOWER, a.role());
        for (long t = 0; t < 100; t++) a.tick(t);
        assertTrue(a.drainOutbox().stream().noneMatch(m -> m instanceof Msg.Prepare), "owned commands are lost");
    }

    @Test
    void singleNodeClusterDecidesAlone() {
        Node a = new Node(0, 1);
        a.submit("solo", 0);
        for (long t = 0; t < 20; t++) a.tick(t);
        assertEquals(List.of("solo"), a.appliedLog());
    }

    private static String valueFor(List<Msg> accepts, int slot) {
        return accepts.stream().map(m -> (Msg.Accept) m).filter(m -> m.slot() == slot)
                .map(Msg.Accept::value).findFirst().orElseThrow();
    }
}
