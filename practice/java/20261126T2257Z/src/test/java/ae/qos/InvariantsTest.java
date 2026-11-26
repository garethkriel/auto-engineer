package ae.qos;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One hand-built corrupt result per rule; each asserts the rule prefix and that the control is clean. */
class InvariantsTest {
    static Packet p(int id, String f, int size, long arr) {
        return new Packet(id, f, size, arr);
    }

    static LinkSimulator.Departure d(Packet p, long s, long e) {
        return new LinkSimulator.Departure(p, s, e);
    }

    static LinkSimulator.Result res(List<LinkSimulator.Departure> ds, List<Packet> drops) {
        return new LinkSimulator.Result(ds, drops);
    }

    static boolean has(List<String> v, String prefix) {
        return v.stream().anyMatch(x -> x.startsWith(prefix));
    }

    static final Packet P1 = p(1, "A", 100, 0);
    static final Packet P2 = p(2, "A", 100, 0);
    static final List<Packet> IN = List.of(P1, P2);

    @Test
    void controlIsClean() {
        assertEquals(List.of(), Invariants.check(IN, res(List.of(d(P1, 0, 1), d(P2, 1, 2)), List.of()), 100));
    }

    @Test
    void conservationMissingDuplicatedAndUnknownPackets() {
        assertTrue(has(Invariants.check(IN, res(List.of(d(P1, 0, 1)), List.of()), 100), "conservation:"));
        assertTrue(has(Invariants.check(IN, res(List.of(d(P1, 0, 1), d(P1, 1, 2)), List.of()), 100), "conservation:"));
        assertTrue(has(Invariants.check(IN, res(List.of(d(P1, 0, 1), d(P2, 1, 2)), List.of(P2)), 100), "conservation:"));
        Packet ghost = p(9, "A", 100, 0);
        assertTrue(has(Invariants.check(IN, res(List.of(d(P1, 0, 1), d(P2, 1, 2), d(ghost, 2, 3)), List.of()), 100), "conservation:"));
    }

    @Test
    void earlyStart() {
        Packet late = p(2, "A", 100, 5);
        List<String> v = Invariants.check(List.of(P1, late), res(List.of(d(P1, 0, 1), d(late, 4, 5)), List.of()), 100);
        assertTrue(has(v, "early-start:"), v.toString());
    }

    @Test
    void durationTooShortAndTooLong() {
        assertTrue(has(Invariants.check(IN, res(List.of(d(P1, 0, 1), d(P2, 1, 1)), List.of()), 100), "duration:"));
        assertTrue(has(Invariants.check(IN, res(List.of(d(P1, 0, 1), d(P2, 1, 3)), List.of()), 100), "duration:"));
        // a size that is not a multiple of the rate must be ceil'ed: 150 B at rate 100 takes 2 ticks
        Packet big = p(3, "A", 150, 0);
        assertEquals(List.of(), Invariants.check(List.of(big), res(List.of(d(big, 0, 2)), List.of()), 100));
        assertTrue(has(Invariants.check(List.of(big), res(List.of(d(big, 0, 1)), List.of()), 100), "duration:"));
    }

    @Test
    void overlap() {
        List<String> v = Invariants.check(IN, res(List.of(d(P1, 0, 1), d(P2, 0, 1)), List.of()), 100);
        assertTrue(has(v, "overlap:"), v.toString());
        // touching intervals are fine (control)
        assertFalse(has(Invariants.check(IN, res(List.of(d(P1, 0, 1), d(P2, 1, 2)), List.of()), 100), "overlap:"));
    }

    @Test
    void idleLinkWhileWorkWaiting() {
        // P2 waited since t=0 but the link idled during [1,5)
        List<String> v = Invariants.check(IN, res(List.of(d(P1, 0, 1), d(P2, 5, 6)), List.of()), 100);
        assertTrue(has(v, "idle:"), v.toString());
        // a gap is fine when the next packet only arrives at its start (control)
        Packet later = p(2, "A", 100, 5);
        assertEquals(List.of(), Invariants.check(List.of(P1, later), res(List.of(d(P1, 0, 1), d(later, 5, 6)), List.of()), 100));
        // the first packet may not start after the earliest arrival either
        assertTrue(has(Invariants.check(List.of(P1), res(List.of(d(P1, 3, 4)), List.of()), 100), "idle:"));
    }

    @Test
    void idleOneTickBeforeStartWhileWorkWaited() {
        Packet late = p(2, "A", 100, 4);
        // late arrived at 4 and the link idled during [2,5): arrival is only one tick before the start
        List<String> v = Invariants.check(List.of(P1, late), res(List.of(d(P1, 0, 1), d(late, 5, 6)), List.of()), 100);
        assertTrue(has(v, "idle:"), v.toString());
    }

    @Test
    void perFlowOrder() {
        List<String> v = Invariants.check(IN, res(List.of(d(P2, 0, 1), d(P1, 1, 2)), List.of()), 100);
        assertTrue(has(v, "flow-order:"), v.toString());
        // different flows may be reordered freely (control)
        Packet b = p(2, "B", 100, 0);
        assertEquals(List.of(), Invariants.check(List.of(P1, b), res(List.of(d(b, 0, 1), d(P1, 1, 2)), List.of()), 100));
    }

    @Test
    void multipleViolationsAreAllReported() {
        List<String> v = Invariants.check(IN, res(List.of(d(P2, 0, 5), d(P1, 3, 4)), List.of()), 100);
        assertTrue(has(v, "duration:") && has(v, "overlap:") && has(v, "flow-order:"), v.toString());
        assertEquals(new ArrayList<>(v).size(), v.size());
    }
}
