package ae.bitemporal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TimelineTest {

    private static Segment<String> seg(long from, long to, String v) {
        return new Segment<>(from, to, v);
    }

    @Test
    void emptyTimelineHasNoValues() {
        Timeline<String> t = Timeline.empty();
        assertTrue(t.segments().isEmpty());
        assertEquals(Optional.empty(), t.valueAt(0));
    }

    @Test
    void assignInEmptyThenReadHalfOpen() {
        Timeline<String> t = Timeline.<String>empty().assign(0, 10, "A");
        assertEquals(Optional.of("A"), t.valueAt(0));
        assertEquals(Optional.of("A"), t.valueAt(9));
        assertEquals(Optional.empty(), t.valueAt(10));
        assertEquals(Optional.empty(), t.valueAt(-1));
    }

    @Test
    void assignInsideSplitsTheSurroundingSegment() {
        Timeline<String> t = Timeline.<String>empty().assign(0, 10, "A").assign(3, 6, "B");
        assertEquals(List.of(seg(0, 3, "A"), seg(3, 6, "B"), seg(6, 10, "A")), t.segments());
    }

    @Test
    void assignOverlappingTwoSegmentsTrimsBoth() {
        Timeline<String> t = Timeline.<String>empty()
                .assign(0, 4, "A").assign(6, 10, "B").assign(2, 8, "C");
        assertEquals(List.of(seg(0, 2, "A"), seg(2, 8, "C"), seg(8, 10, "B")), t.segments());
    }

    @Test
    void adjacentEqualValuesCoalesce() {
        Timeline<String> t = Timeline.<String>empty().assign(0, 5, "A").assign(5, 10, "A");
        assertEquals(List.of(seg(0, 10, "A")), t.segments());
    }

    @Test
    void adjacentDifferentValuesStaySeparate() {
        Timeline<String> t = Timeline.<String>empty().assign(0, 5, "A").assign(5, 10, "B");
        assertEquals(List.of(seg(0, 5, "A"), seg(5, 10, "B")), t.segments());
    }

    @Test
    void assigningTheSameValueInsideChangesNothing() {
        Timeline<String> a = Timeline.<String>empty().assign(0, 10, "A");
        assertEquals(a, a.assign(2, 4, "A"));
    }

    @Test
    void healingAGapCoalescesThreeSegmentsIntoOne() {
        Timeline<String> t = Timeline.<String>empty().assign(0, 10, "A").clear(4, 6).assign(4, 6, "A");
        assertEquals(List.of(seg(0, 10, "A")), t.segments());
    }

    @Test
    void clearMiddleLeavesTwoPieces() {
        Timeline<String> t = Timeline.<String>empty().assign(0, 10, "A").clear(4, 6);
        assertEquals(List.of(seg(0, 4, "A"), seg(6, 10, "A")), t.segments());
    }

    @Test
    void clearEdgesAndWholeSegment() {
        Timeline<String> base = Timeline.<String>empty().assign(0, 10, "A");
        assertEquals(List.of(seg(3, 10, "A")), base.clear(-5, 3).segments());
        assertEquals(List.of(seg(0, 7, "A")), base.clear(7, 20).segments());
        assertTrue(base.clear(0, 10).segments().isEmpty());
        assertTrue(base.clear(-100, 100).segments().isEmpty());
    }

    @Test
    void clearOutsideAnythingReturnsEqualTimeline() {
        Timeline<String> base = Timeline.<String>empty().assign(5, 10, "A");
        assertEquals(base, base.clear(0, 5));
        assertEquals(base, base.clear(10, 20));
    }

    @Test
    void foreverIsAnOrdinaryUpperBound() {
        Timeline<String> t = Timeline.<String>empty().assign(5, Segment.FOREVER, "X");
        assertEquals(Optional.of("X"), t.valueAt(Long.MAX_VALUE - 1));
        assertEquals(Optional.empty(), t.valueAt(Long.MAX_VALUE));
        assertEquals(List.of(seg(5, 100, "X")), t.clear(100, Segment.FOREVER).segments());
    }

    @Test
    void negativeValidTimesWork() {
        Timeline<String> t = Timeline.<String>empty().assign(Long.MIN_VALUE, 0, "OLD");
        assertEquals(Optional.of("OLD"), t.valueAt(Long.MIN_VALUE));
        assertEquals(Optional.empty(), t.valueAt(0));
    }

    @Test
    void segmentsViewIsUnmodifiable() {
        Timeline<String> t = Timeline.<String>empty().assign(0, 1, "A");
        assertThrows(UnsupportedOperationException.class, () -> t.segments().clear());
    }

    @Test
    void invalidArgumentsAreRejected() {
        Timeline<String> t = Timeline.empty();
        assertThrows(IllegalArgumentException.class, () -> t.assign(5, 5, "A"));
        assertThrows(IllegalArgumentException.class, () -> t.assign(6, 5, "A"));
        assertThrows(IllegalArgumentException.class, () -> t.assign(0, 5, null));
        assertThrows(IllegalArgumentException.class, () -> t.clear(5, 5));
        assertThrows(IllegalArgumentException.class, () -> new Segment<>(3, 3, "A"));
        assertThrows(IllegalArgumentException.class, () -> new Segment<>(0, 1, null));
    }
}
