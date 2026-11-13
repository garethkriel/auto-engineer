package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntervalTest {
    @Test
    void rejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class, () -> Interval.of(5, 4));
    }

    @Test
    void lengthDoesNotOverflow() {
        assertEquals(4294967295L, Interval.of(Integer.MIN_VALUE, Integer.MAX_VALUE).length());
        assertEquals(0L, Interval.of(3, 3).length());
    }

    @Test
    void containsIsInclusive() {
        Interval i = Interval.of(2, 5);
        assertTrue(i.contains(2));
        assertTrue(i.contains(5));
        assertFalse(i.contains(1));
        assertFalse(i.contains(6));
    }

    @Test
    void touchingEndpointsOverlap() {
        assertTrue(Interval.of(1, 3).overlaps(Interval.of(3, 6)));
        assertTrue(Interval.of(3, 6).overlaps(Interval.of(1, 3)));
        assertFalse(Interval.of(1, 3).overlaps(Interval.of(4, 6)));
    }

    @Test
    void mergeCoversBothIncludingAdjacent() {
        assertEquals(Interval.of(1, 6), Interval.of(1, 3).merge(Interval.of(4, 6)));
        assertEquals(Interval.of(1, 9), Interval.of(1, 9).merge(Interval.of(2, 3)));
    }

    @Test
    void mergeOfAdjacentAtIntMaxDoesNotOverflow() {
        assertEquals(Interval.of(1, Integer.MAX_VALUE),
                Interval.of(1, Integer.MAX_VALUE).merge(Interval.of(Integer.MAX_VALUE, Integer.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class,
                () -> Interval.of(Integer.MIN_VALUE, Integer.MAX_VALUE - 2).merge(Interval.of(Integer.MAX_VALUE, Integer.MAX_VALUE)));
    }

    @Test
    void mergeOfGapThrows() {
        assertThrows(IllegalArgumentException.class, () -> Interval.of(1, 2).merge(Interval.of(4, 5)));
    }

    @Test
    void mergeAllSortsAndCollapses() {
        List<Interval> in = new ArrayList<>(List.of(
                Interval.of(8, 10), Interval.of(1, 3), Interval.of(2, 6), Interval.of(4, 5), Interval.of(11, 12), Interval.of(20, 20)));
        List<Interval> copy = List.copyOf(in);
        assertEquals(List.of(Interval.of(1, 6), Interval.of(8, 12), Interval.of(20, 20)), Interval.mergeAll(in));
        assertEquals(copy, in);
    }

    @Test
    void mergeAllEmptyAndNull() {
        assertEquals(List.of(), Interval.mergeAll(List.of()));
        assertThrows(NullPointerException.class, () -> Interval.mergeAll(null));
    }

    @Test
    void mergeAllHandlesIntMaxEnd() {
        assertEquals(List.of(Interval.of(5, Integer.MAX_VALUE)),
                Interval.mergeAll(List.of(Interval.of(Integer.MAX_VALUE, Integer.MAX_VALUE), Interval.of(5, 7), Interval.of(6, Integer.MAX_VALUE - 1))));
    }

    @Test
    void equalsHashCodeToStringCompareTo() {
        assertEquals(Interval.of(1, 5), Interval.of(1, 5));
        assertEquals(Interval.of(1, 5).hashCode(), Interval.of(1, 5).hashCode());
        assertNotEquals(Interval.of(1, 5), Interval.of(1, 6));
        assertNotEquals(Interval.of(1, 5), null);
        assertEquals("[1, 5]", Interval.of(1, 5).toString());
        assertTrue(Interval.of(1, 5).compareTo(Interval.of(2, 2)) < 0);
        assertTrue(Interval.of(1, 5).compareTo(Interval.of(1, 4)) > 0);
        assertEquals(0, Interval.of(1, 5).compareTo(Interval.of(1, 5)));
    }

    @Test
    void createdCountIncreasesPerInstance() {
        long before = Interval.createdCount();
        Interval.of(1, 2);
        Interval.of(1, 2);
        assertEquals(before + 2, Interval.createdCount());
        assertThrows(IllegalArgumentException.class, () -> Interval.of(2, 1));
        assertEquals(before + 2, Interval.createdCount());
    }
}
