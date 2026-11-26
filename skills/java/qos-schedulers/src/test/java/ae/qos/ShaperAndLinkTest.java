package ae.qos;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShaperAndLinkTest {
    static Packet p(int id, String flow, int size, long arrival) {
        return new Packet(id, flow, size, arrival);
    }

    static List<Long> releases(List<Packet> shaped) {
        List<Long> out = new ArrayList<>();
        for (Packet x : shaped) out.add(x.arrival());
        return out;
    }

    /* rate 10/tick burst 100, four 60-byte packets at t=0:
       p1 t=0 (40 left); p2 needs 20 more -> +2 ticks -> t=2 (tokens 60 -> 0); p3 +6 -> t=8; p4 +6 -> t=14. */
    @Test
    void shaperLiteralRelease() {
        List<Packet> in = List.of(p(1, "A", 60, 0), p(2, "A", 60, 0), p(3, "A", 60, 0), p(4, "A", 60, 0));
        assertEquals(List.of(0L, 2L, 8L, 14L), releases(TokenBucketShaper.shape(in, 10, 100)));
    }

    /* After a long idle period the bucket is capped at burst: p at 100 releases at once, the next 100 bytes wait 10 ticks. */
    @Test
    void shaperBucketCapsAtBurst() {
        List<Packet> in = List.of(p(1, "A", 100, 0), p(2, "A", 100, 100), p(3, "A", 100, 100));
        // p1 t=0 (0 left); p2 at 100: min(100, 0+1000)=100 -> t=100; p3 needs 100 -> +10 -> t=110.
        assertEquals(List.of(0L, 100L, 110L), releases(TokenBucketShaper.shape(in, 10, 100)));
    }

    @Test
    void shaperCeilsPartialTicks() {
        // rate 3, burst 10: p1 size 10 at 0 (0 left); p2 size 10 needs 10 tokens: ceil(10/3)=4 ticks (12 tokens, cap 10).
        List<Packet> in = List.of(p(1, "A", 10, 0), p(2, "A", 10, 0));
        assertEquals(List.of(0L, 4L), releases(TokenBucketShaper.shape(in, 3, 10)));
    }

    @Test
    void shaperKeepsFlowsIndependentAndSortsByRelease() {
        List<Packet> in = List.of(p(1, "A", 100, 0), p(2, "A", 100, 0), p(3, "B", 100, 0));
        List<Packet> out = TokenBucketShaper.shape(in, 10, 100);
        // A: 0, 10 ; B: 0 (own bucket). Sorted by (release, input position): 1, 3, 2
        assertEquals(List.of(1, 3, 2), out.stream().map(Packet::id).toList());
        assertEquals(List.of(0L, 0L, 10L), releases(out));
    }

    @Test
    void shaperPreservesOrderWhenInputUnsortedAndRejectsOversize() {
        List<Packet> in = List.of(p(2, "A", 100, 5), p(1, "A", 100, 0));
        List<Packet> out = TokenBucketShaper.shape(in, 10, 100);
        assertEquals(List.of(1, 2), out.stream().map(Packet::id).toList());
        assertEquals(List.of(0L, 10L), releases(out)); // p1 at 0; p2 arrives 5, tokens 50 -> needs 50 more -> t=10
        assertThrows(IllegalArgumentException.class, () -> TokenBucketShaper.shape(List.of(p(1, "A", 101, 0)), 10, 100));
        assertThrows(IllegalArgumentException.class, () -> TokenBucketShaper.shape(in, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> TokenBucketShaper.shape(in, 10, 0));
    }

    /* rate 100: P0 250B@0 -> 3 ticks [0,3); P1 100B@1 -> [3,4); P2 50B@10 -> [10,11) after an idle gap. */
    @Test
    void linkLiteralTimelineWithIdleGap() {
        List<Packet> in = List.of(p(0, "A", 250, 0), p(1, "A", 100, 1), p(2, "A", 50, 10));
        LinkSimulator.Result r = LinkSimulator.run(in, new FifoScheduler(), 100, Long.MAX_VALUE);
        assertEquals(List.of(0L, 3L, 10L), r.departures().stream().map(LinkSimulator.Departure::start).toList());
        assertEquals(List.of(3L, 4L, 11L), r.departures().stream().map(LinkSimulator.Departure::end).toList());
        assertTrue(r.drops().isEmpty());
        assertEquals(List.of(), Invariants.check(in, r, 100));
    }

    /* buffer 200: at t=0 A1, A2 admitted (backlog 200), A3 dropped. A1 [0,1). A4 arrives at 1: backlog is A2 (100)
       because A1 left the queue, so 100+100 <= 200 -> admitted. A2 [1,2), A4 [2,3). */
    @Test
    void linkTailDropAndAdmissionAtCompletionTick() {
        List<Packet> in = List.of(p(1, "A", 100, 0), p(2, "A", 100, 0), p(3, "A", 100, 0), p(4, "A", 100, 1));
        LinkSimulator.Result r = LinkSimulator.run(in, new FifoScheduler(), 100, 200);
        assertEquals(List.of(3), r.drops().stream().map(Packet::id).toList());
        assertEquals(List.of(1, 2, 4), r.departures().stream().map(d -> d.packet().id()).toList());
        assertEquals(List.of(0L, 1L, 2L), r.departures().stream().map(LinkSimulator.Departure::start).toList());
        assertEquals(List.of(), Invariants.check(in, r, 100));
    }

    @Test
    void linkDropsPacketBiggerThanBufferEvenWhenIdle() {
        List<Packet> in = List.of(p(1, "A", 300, 0));
        LinkSimulator.Result r = LinkSimulator.run(in, new FifoScheduler(), 100, 200);
        assertEquals(1, r.drops().size());
        assertTrue(r.departures().isEmpty());
    }

    @Test
    void linkSortsInputByArrivalStablyAndRejectsDuplicateIds() {
        List<Packet> in = List.of(p(1, "A", 100, 5), p(2, "A", 100, 0), p(3, "A", 100, 5));
        LinkSimulator.Result r = LinkSimulator.run(in, new FifoScheduler(), 100, Long.MAX_VALUE);
        assertEquals(List.of(2, 1, 3), r.departures().stream().map(d -> d.packet().id()).toList());
        List<Packet> dup = List.of(p(1, "A", 100, 0), p(1, "A", 100, 1));
        assertThrows(IllegalArgumentException.class, () -> LinkSimulator.run(dup, new FifoScheduler(), 100, 10));
        assertThrows(IllegalArgumentException.class, () -> LinkSimulator.run(in, new FifoScheduler(), 0, 10));
    }

    /* Non-preemptive: a high priority packet arriving mid-transmission waits for the end of the low one. */
    @Test
    void linkIsNonPreemptiveAndPriorityPicksNextAtCompletion() {
        PriorityScheduler s = new PriorityScheduler(Map.of("hi", 0, "lo", 1));
        List<Packet> in = List.of(p(1, "lo", 500, 0), p(2, "lo", 100, 0), p(3, "hi", 100, 2));
        LinkSimulator.Result r = LinkSimulator.run(in, s, 100, Long.MAX_VALUE);
        assertEquals(List.of(1, 3, 2), r.departures().stream().map(d -> d.packet().id()).toList());
        assertEquals(5L, r.departures().get(1).start());
    }

    @Test
    void statsSummariseFlows() {
        List<Packet> in = List.of(p(1, "A", 100, 0), p(2, "B", 100, 0), p(3, "A", 100, 0));
        LinkSimulator.Result r = LinkSimulator.run(in, new FifoScheduler(), 100, Long.MAX_VALUE);
        Map<String, Stats.FlowStats> st = Stats.of(r);
        // FIFO order P1 [0,1), P2 [1,2), P3 [2,3); delay = end - arrival.
        assertEquals(new Stats.FlowStats(2, 200, 3), st.get("A"));
        assertEquals(new Stats.FlowStats(1, 100, 2), st.get("B"));
        assertEquals(List.of("A", "B"), List.copyOf(st.keySet()));
    }
}
