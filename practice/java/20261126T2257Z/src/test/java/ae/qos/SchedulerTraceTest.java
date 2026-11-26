package ae.qos;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Hand-derived literal traces for each scheduler. */
class SchedulerTraceTest {
    static Packet p(int id, String flow, int size) {
        return new Packet(id, flow, size, 0);
    }

    static List<Integer> drain(Scheduler s) {
        List<Integer> ids = new ArrayList<>();
        while (s.dequeue().map(x -> ids.add(x.id())).isPresent()) { /* keep draining */ }
        return ids;
    }

    @Test
    void packetValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Packet(1, " ", 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new Packet(1, "A", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Packet(1, "A", 10, -1));
        assertThrows(NullPointerException.class, () -> new Packet(1, null, 10, 0));
    }

    @Test
    void fifoKeepsEnqueueOrderAndTracksBacklog() {
        FifoScheduler s = new FifoScheduler();
        s.enqueue(p(1, "A", 10));
        s.enqueue(p(2, "B", 20));
        s.enqueue(p(3, "A", 5));
        assertEquals(35, s.backlogBytes());
        assertEquals(1, s.dequeue().orElseThrow().id());
        assertEquals(25, s.backlogBytes());
        assertEquals(List.of(2, 3), drain(s));
        assertTrue(s.isEmpty());
        assertTrue(s.dequeue().isEmpty());
    }

    @Test
    void priorityIsStrictAndFifoWithinAClass() {
        PriorityScheduler s = new PriorityScheduler(Map.of("hi", 0, "mid", 1, "mid2", 1, "lo", 2));
        s.enqueue(p(1, "lo", 10));
        s.enqueue(p(2, "mid2", 10));
        s.enqueue(p(3, "mid", 10));
        s.enqueue(p(4, "hi", 10));
        s.enqueue(p(5, "hi", 10));
        // hi first (4,5), then mid2 before mid because it was enqueued first, then lo.
        assertEquals(List.of(4, 5, 2, 3, 1), drain(s));
    }

    @Test
    void priorityStarvesLowClassWhileHighKeepsArriving() {
        PriorityScheduler s = new PriorityScheduler(Map.of("hi", 0, "lo", 1));
        s.enqueue(p(1, "lo", 10));
        for (int i = 0; i < 5; i++) {
            s.enqueue(p(10 + i, "hi", 10));
            assertEquals(10 + i, s.dequeue().orElseThrow().id());
        }
        assertEquals(1, s.dequeue().orElseThrow().id());
    }

    @Test
    void unknownFlowIsRejectedWithoutSideEffects() {
        List<Scheduler> all = List.of(new PriorityScheduler(Map.of("A", 0)),
                new DrrScheduler(Map.of("A", 100)), new WfqScheduler(Map.of("A", 1)));
        for (Scheduler s : all) {
            s.enqueue(p(1, "A", 10));
            assertThrows(IllegalArgumentException.class, () -> s.enqueue(p(2, "Z", 10)));
            assertEquals(10, s.backlogBytes(), s.getClass().getSimpleName());
            assertEquals(List.of(1), drain(s));
        }
    }

    @Test
    void constructorsCopyTheirMaps() {
        Map<String, Integer> quanta = new LinkedHashMap<>(Map.of("A", 100));
        DrrScheduler s = new DrrScheduler(quanta);
        quanta.put("B", 100);
        assertThrows(IllegalArgumentException.class, () -> s.enqueue(p(1, "B", 10)));
        Map<String, Integer> w = new LinkedHashMap<>(Map.of("A", 1));
        WfqScheduler q = new WfqScheduler(w);
        w.put("B", 1);
        assertThrows(IllegalArgumentException.class, () -> q.enqueue(p(1, "B", 10)));
        Map<String, Integer> pr = new LinkedHashMap<>(Map.of("A", 1));
        PriorityScheduler r = new PriorityScheduler(pr);
        pr.put("B", 1);
        assertThrows(IllegalArgumentException.class, () -> r.enqueue(p(1, "B", 10)));
    }

    @Test
    void badParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DrrScheduler(Map.of("A", 0)));
        assertThrows(IllegalArgumentException.class, () -> new WfqScheduler(Map.of("A", 0)));
        assertThrows(IllegalArgumentException.class, () -> new DrrScheduler(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new WfqScheduler(Map.of()));
    }

    /*
     * DRR, quanta A=500 B=300, enqueue order A1(300) A2(300) A3(300) B1(200) B2(200) B3(200).
     * turn A: deficit 500 -> A1 (200 left), A2 does not fit -> A to tail.      out A1
     * turn B: deficit 300 -> B1 (100 left), B2 does not fit -> B to tail.      out B1
     * turn A: 200+500=700 -> A2 (400), A3 (100), queue empty -> deficit 0.     out A2 A3
     * turn B: 100+300=400 -> B2 (200), B3 (0), queue empty.                    out B2 B3
     */
    @Test
    void drrLiteralTraceCarriesDeficit() {
        Map<String, Integer> q = new LinkedHashMap<>();
        q.put("A", 500);
        q.put("B", 300);
        DrrScheduler s = new DrrScheduler(q);
        s.enqueue(p(1, "A", 300));
        s.enqueue(p(2, "A", 300));
        s.enqueue(p(3, "A", 300));
        s.enqueue(p(4, "B", 200));
        s.enqueue(p(5, "B", 200));
        s.enqueue(p(6, "B", 200));
        assertEquals(List.of(1, 4, 2, 3, 5, 6), drain(s));
    }

    /* A packet larger than the quantum is served after several credits: quantum 100, packet 350
       needs credits on 4 turns while B (quantum 100, packets of 100) keeps being served. */
    @Test
    void drrBigPacketAccumulatesCreditAcrossTurns() {
        Map<String, Integer> q = new LinkedHashMap<>();
        q.put("A", 100);
        q.put("B", 100);
        DrrScheduler s = new DrrScheduler(q);
        s.enqueue(p(1, "A", 350));
        for (int i = 0; i < 5; i++) s.enqueue(p(10 + i, "B", 100));
        // turn1 A:100 no; B:100 -> B10. turn2 A:200 no; B11. turn3 A:300 no; B12. turn4 A:400 -> A1 (50 left)
        // then queue empty -> A leaves; B continues 13, 14.
        assertEquals(List.of(10, 11, 12, 1, 13, 14), drain(s));
    }

    /* Credit is not hoarded: B goes idle (deficit reset) and returns; it gets exactly one quantum. */
    @Test
    void drrIdleFlowDoesNotHoardCredit() {
        Map<String, Integer> q = new LinkedHashMap<>();
        q.put("A", 100);
        q.put("B", 100);
        DrrScheduler s = new DrrScheduler(q);
        s.enqueue(p(1, "B", 10)); // B uses 10 of 100, queue empty -> deficit reset to 0 (not 90)
        assertEquals(1, s.dequeue().orElseThrow().id());
        s.enqueue(p(2, "A", 100));
        s.enqueue(p(3, "A", 100));
        s.enqueue(p(4, "B", 100));
        s.enqueue(p(5, "B", 80));
        // A:100 -> A2 ; A3 needs credit -> tail. B: 100 (hoarded credit 190 would also fit B5=80 after B4) -> B4 only. then A3, B5.
        assertEquals(List.of(2, 4, 3, 5), drain(s));
    }

    /*
     * WFQ weights A=2 B=1 -> scale 2; increments A: size*1, B: size*2. All enqueued at V=0:
     * A1(100) tag 100, A2 200, A3 300, B1 tag 200 (seq 3), B2 400.
     * order by (tag, seq): A1(100) A2(200,seq1) B1(200,seq3) A3(300) B2(400).
     */
    @Test
    void wfqLiteralTraceOrdersByFinishTag() {
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("A", 2);
        w.put("B", 1);
        WfqScheduler s = new WfqScheduler(w);
        s.enqueue(p(1, "A", 100));
        s.enqueue(p(2, "A", 100));
        s.enqueue(p(3, "A", 100));
        s.enqueue(p(4, "B", 100));
        s.enqueue(p(5, "B", 100));
        assertEquals(List.of(1, 2, 4, 3, 5), drain(s));
    }

    /* An idle flow re-enters at the current virtual time, not at its stale last finish tag. */
    @Test
    void wfqIdleFlowStartsAtVirtualTime() {
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("A", 2);
        w.put("B", 1);
        WfqScheduler s = new WfqScheduler(w);
        s.enqueue(p(1, "A", 100)); // tag 100
        s.enqueue(p(2, "A", 100)); // 200
        s.enqueue(p(3, "A", 100)); // 300
        assertEquals(1, s.dequeue().orElseThrow().id()); // V=100
        assertEquals(2, s.dequeue().orElseThrow().id()); // V=200
        s.enqueue(p(4, "B", 100)); // start max(0,200)=200, tag 200+200=400 (stale start 0 would give 200 and jump the queue)
        assertEquals(List.of(3, 4), drain(s));
    }

    @Test
    void wfqSameFlowStaysFifoAndEqualTagsBreakByEnqueueOrder() {
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("A", 1);
        w.put("B", 1);
        WfqScheduler s = new WfqScheduler(w);
        s.enqueue(p(1, "B", 50));
        s.enqueue(p(2, "A", 50));
        s.enqueue(p(3, "A", 50));
        s.enqueue(p(4, "B", 50));
        // tags: B1 50, A2 50, A3 100, B4 100 -> ties by seq
        assertEquals(List.of(1, 2, 3, 4), drain(s));
    }

    /* weights A=1, B=2^31-1 (prime): scale 2^31-1, A's increment is size*(2^31-1) ~ 4.6e18 for a max-size packet,
       so two fit in a long (9.2233720282e18 < Long.MAX) and the third overflows. */
    @Test
    void wfqOverflowLeavesStateUntouched() {
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("A", 1);
        w.put("B", Integer.MAX_VALUE);
        WfqScheduler t = new WfqScheduler(w);
        t.enqueue(p(1, "A", Integer.MAX_VALUE));
        t.enqueue(p(2, "A", Integer.MAX_VALUE));
        long before = t.backlogBytes();
        assertThrows(ArithmeticException.class, () -> t.enqueue(p(3, "A", Integer.MAX_VALUE)));
        assertEquals(before, t.backlogBytes());
        assertEquals(List.of(1, 2), drain(t));
    }

    @Test
    void wfqWeightLcmOverflowRejectedAtConstruction() {
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("A", 2_147_483_647);
        w.put("B", 2_147_483_629);
        w.put("C", 2_147_483_587);
        assertThrows(ArithmeticException.class, () -> new WfqScheduler(w));
    }
}
