package ae.broker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrokerTest {
    AtomicLong now;
    Broker b;

    @BeforeEach
    void setUp() {
        now = new AtomicLong(1000);
        b = new Broker(now::get, 100);
        b.createTopic("t", 4);
    }

    static TopicPartition tp(String t, int p) { return new TopicPartition(t, p); }

    @Test
    void keyedProduceIsStableAndOffsetsIncrease() {
        Broker.Metadata a = b.produce("t", "k", "v1");
        Broker.Metadata c = b.produce("t", "k", "v2");
        assertEquals(Math.floorMod("k".hashCode(), 4), a.partition());
        assertEquals(a.partition(), c.partition());
        assertEquals(0, a.offset());
        assertEquals(1, c.offset());
    }

    @Test
    void nullKeyRoundRobinsFromZero() {
        List<Integer> parts = new ArrayList<>();
        for (int i = 0; i < 6; i++) parts.add(b.produce("t", null, "v" + i).partition());
        assertEquals(List.of(0, 1, 2, 3, 0, 1), parts);
    }

    @Test
    void unknownTopicAndDuplicateTopicRejected() {
        assertThrows(IllegalArgumentException.class, () -> b.produce("nope", "k", "v"));
        assertThrows(IllegalArgumentException.class, () -> b.createTopic("t", 2));
        assertThrows(IllegalArgumentException.class, () -> b.createTopic("z", 0));
        assertThrows(IllegalArgumentException.class, () -> b.join("g", "m", java.util.Set.of("nope")));
    }

    @Test
    void singleMemberGetsAllPartitionsSorted() {
        Broker.Membership m = b.join("g", "m1", java.util.Set.of("t"));
        assertEquals(1, m.generation());
        assertEquals(List.of(tp("t", 0), tp("t", 1), tp("t", 2), tp("t", 3)), m.assignment());
    }

    @Test
    void secondMemberSplitsRoundRobinAndBumpsGeneration() {
        b.join("g", "a", java.util.Set.of("t"));
        Broker.Membership mb = b.join("g", "b", java.util.Set.of("t"));
        assertEquals(2, mb.generation());
        assertEquals(List.of(tp("t", 1), tp("t", 3)), mb.assignment());
        assertEquals(List.of(tp("t", 0), tp("t", 2)), b.heartbeat("g", "a").assignment());
    }

    @Test
    void rejoinWithSameSubscriptionIsNoOp() {
        b.join("g", "a", java.util.Set.of("t"));
        Broker.Membership again = b.join("g", "a", java.util.Set.of("t"));
        assertEquals(1, again.generation());
    }

    @Test
    void changedSubscriptionRebalances() {
        b.createTopic("u", 2);
        b.join("g", "a", java.util.Set.of("t"));
        Broker.Membership m = b.join("g", "a", java.util.Set.of("t", "u"));
        assertEquals(2, m.generation());
        assertEquals(List.of(tp("t", 0), tp("t", 1), tp("t", 2), tp("t", 3), tp("u", 0), tp("u", 1)), m.assignment());
    }

    @Test
    void pollReturnsMessagesInAssignmentOrderAndAdvances() {
        for (int i = 0; i < 4; i++) b.produce("t", null, "v" + i); // one per partition
        b.produce("t", null, "v4"); // partition 0 again, offset 1
        Broker.Membership m = b.join("g", "a", java.util.Set.of("t"));
        List<Broker.Message> got = b.poll("g", "a", m.generation(), 10);
        assertEquals(List.of("v0", "v4", "v1", "v2", "v3"), got.stream().map(Broker.Message::value).toList());
        assertEquals(List.of(0L, 1L, 0L, 0L, 0L), got.stream().map(Broker.Message::offset).toList());
        assertTrue(b.poll("g", "a", m.generation(), 10).isEmpty());
    }

    @Test
    void pollRespectsMax() {
        for (int i = 0; i < 8; i++) b.produce("t", null, "v" + i);
        Broker.Membership m = b.join("g", "a", java.util.Set.of("t"));
        assertEquals(3, b.poll("g", "a", m.generation(), 3).size());
        assertEquals(5, b.poll("g", "a", m.generation(), 100).size());
        assertThrows(IllegalArgumentException.class, () -> b.poll("g", "a", m.generation(), 0));
    }

    @Test
    void staleGenerationRejectedAndChangesNothing() {
        b.produce("t", "k", "v");
        Broker.Membership m1 = b.join("g", "a", java.util.Set.of("t"));
        b.join("g", "b", java.util.Set.of("t"));
        assertThrows(StaleGenerationException.class, () -> b.poll("g", "a", m1.generation(), 5));
        assertThrows(StaleGenerationException.class,
                () -> b.commit("g", "a", m1.generation(), tp("t", 0), 0));
        assertEquals(-1, b.committed("g", tp("t", 0)));
    }

    @Test
    void commitOnlyOwnedPartitionAndWithinPosition() {
        for (int i = 0; i < 4; i++) b.produce("t", null, "v" + i);
        b.join("g", "a", java.util.Set.of("t"));
        Broker.Membership mb = b.join("g", "b", java.util.Set.of("t"));
        int gen = mb.generation();
        b.poll("g", "b", gen, 10); // b owns 1 and 3, positions now 1 and 1
        assertThrows(IllegalStateException.class, () -> b.commit("g", "b", gen, tp("t", 0), 0));
        assertThrows(IllegalArgumentException.class, () -> b.commit("g", "b", gen, tp("t", 1), 2));
        assertThrows(IllegalArgumentException.class, () -> b.commit("g", "b", gen, tp("t", 1), -1));
        b.commit("g", "b", gen, tp("t", 1), 1);
        assertEquals(1, b.committed("g", tp("t", 1)));
    }

    @Test
    void rebalanceRedeliversUncommittedMessagesOnly() {
        for (int i = 0; i < 8; i++) b.produce("t", null, "v" + i); // 2 per partition
        Broker.Membership m = b.join("g", "a", java.util.Set.of("t"));
        assertEquals(8, b.poll("g", "a", m.generation(), 100).size());
        b.commit("g", "a", m.generation(), tp("t", 0), 2);
        b.commit("g", "a", m.generation(), tp("t", 1), 1);
        Broker.Membership m2 = b.join("g", "b", java.util.Set.of("t")); // b: 1,3 ; a: 0,2
        List<Broker.Message> forB = b.poll("g", "b", m2.generation(), 100);
        assertEquals(List.of("1:1", "3:0", "3:1"),
                forB.stream().map(x -> x.partition() + ":" + x.offset()).toList());
        List<Broker.Message> forA = b.poll("g", "a", m2.generation(), 100);
        assertEquals(List.of("2:0", "2:1"),
                forA.stream().map(x -> x.partition() + ":" + x.offset()).toList());
    }

    @Test
    void sessionExpiryEvictsSilentMemberAndRebalances() {
        b.join("g", "a", java.util.Set.of("t"));
        b.join("g", "b", java.util.Set.of("t"));
        now.addAndGet(60);
        b.heartbeat("g", "a");
        now.addAndGet(60); // a seen 60 ago, b seen 120 ago > 100
        Broker.Membership ma = b.heartbeat("g", "a");
        assertEquals(3, ma.generation());
        assertEquals(4, ma.assignment().size());
        assertThrows(UnknownMemberException.class, () -> b.heartbeat("g", "b"));
        assertThrows(UnknownMemberException.class, () -> b.poll("g", "b", 3, 1));
    }

    @Test
    void exactTimeoutBoundaryIsStillAlive() {
        b.join("g", "a", java.util.Set.of("t"));
        now.addAndGet(100);
        assertEquals(1, b.heartbeat("g", "a").generation());
        now.addAndGet(101);
        assertThrows(UnknownMemberException.class, () -> b.heartbeat("g", "a"));
    }

    @Test
    void leaveRebalancesAndCommitsSurvive() {
        for (int i = 0; i < 4; i++) b.produce("t", null, "v" + i);
        b.join("g", "a", java.util.Set.of("t"));
        Broker.Membership mb = b.join("g", "b", java.util.Set.of("t"));
        b.poll("g", "b", mb.generation(), 10);
        b.commit("g", "b", mb.generation(), tp("t", 1), 1);
        b.leave("g", "b");
        Broker.Membership ma = b.heartbeat("g", "a");
        assertEquals(3, ma.generation());
        assertEquals(1, b.committed("g", tp("t", 1)));
        List<Broker.Message> got = b.poll("g", "a", 3, 10);
        assertEquals(3, got.size()); // partition 1 resumes at committed 1 => nothing; 0,2,3 each 1
        assertThrows(UnknownMemberException.class, () -> b.leave("g", "b"));
    }

    @Test
    void retentionMovesLogStartAndPollJumpsForward() {
        for (int i = 0; i < 5; i++) b.produce("t", "same", "v" + i);
        int p = Math.floorMod("same".hashCode(), 4);
        Broker.Membership m = b.join("g", "a", java.util.Set.of("t"));
        b.retain("t", p, 2);
        assertEquals(3, b.logStart("t", p));
        assertEquals(5, b.logEnd("t", p));
        List<Broker.Message> got = b.poll("g", "a", m.generation(), 10);
        assertEquals(List.of("v3", "v4"), got.stream().map(Broker.Message::value).toList());
        b.produce("t", "same", "v5");
        assertEquals(6, b.logEnd("t", p));
        assertEquals(1, b.poll("g", "a", m.generation(), 10).size());
    }

    @Test
    void lagCountsUncommittedRetainedMessages() {
        for (int i = 0; i < 8; i++) b.produce("t", null, "v" + i);
        Broker.Membership m = b.join("g", "a", java.util.Set.of("t"));
        assertEquals(8, b.lag("g"));
        b.poll("g", "a", m.generation(), 100);
        assertEquals(8, b.lag("g")); // polled but not committed
        b.commit("g", "a", m.generation(), tp("t", 0), 2);
        assertEquals(6, b.lag("g"));
        b.retain("t", 1, 1); // partition 1 had 2, now logStart 1
        assertEquals(5, b.lag("g"));
        b.retain("t", 0, 0); // committed 2 == logEnd, nothing lost for lag
        assertEquals(5, b.lag("g"));
    }

    @Test
    void independentGroupsKeepIndependentOffsets() {
        for (int i = 0; i < 4; i++) b.produce("t", null, "v" + i);
        Broker.Membership g1 = b.join("g1", "a", java.util.Set.of("t"));
        Broker.Membership g2 = b.join("g2", "a", java.util.Set.of("t"));
        assertEquals(4, b.poll("g1", "a", g1.generation(), 10).size());
        b.commit("g1", "a", g1.generation(), tp("t", 0), 1);
        assertEquals(4, b.poll("g2", "a", g2.generation(), 10).size());
        assertEquals(-1, b.committed("g2", tp("t", 0)));
    }

    @Test
    void unsubscribedTopicPartitionsNotAssignedToThatMember() {
        b.createTopic("u", 2);
        b.join("g", "a", java.util.Set.of("t", "u"));
        Broker.Membership mb = b.join("g", "b", java.util.Set.of("u"));
        assertEquals(List.of(tp("u", 1)), mb.assignment());
        assertEquals(List.of(tp("t", 0), tp("t", 1), tp("t", 2), tp("t", 3), tp("u", 0)),
                b.heartbeat("g", "a").assignment());
    }

    @Test
    void assignmentsViewDoesNotRefreshSessions() {
        b.join("g", "a", java.util.Set.of("t"));
        now.addAndGet(100);
        assertEquals(List.of("a"), List.copyOf(b.assignments("g").keySet()));
        now.addAndGet(1); // a last seen 101 ago; the view must not have refreshed it
        assertThrows(UnknownMemberException.class, () -> b.heartbeat("g", "a"));
    }
}
