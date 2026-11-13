package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IntervalTest {
    @Test void rejectsReversedBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Interval(5, 4));
    }
    @Test void singlePointAllowed() {
        assertEquals(1, new Interval(5, 5).length());
    }
    @Test void lengthDoesNotOverflow() {
        assertEquals(4294967296L, new Interval(Integer.MIN_VALUE, Integer.MAX_VALUE).length());
    }
    @Test void parseValid() {
        assertEquals(new Interval(3, 7), Interval.parse(" [ 3 , 7 ] "));
        assertEquals(new Interval(-4, -1), Interval.parse("[-4,-1]"));
    }
    @Test void parseInvalid() {
        for (String s : new String[] {"3,7", "[3;7]", "[a,b]", "[7,3]", "", "[3,]"}) {
            assertThrows(IllegalArgumentException.class, () -> Interval.parse(s), s);
        }
        assertThrows(NullPointerException.class, () -> Interval.parse(null));
    }
    @Test void containsIsInclusive() {
        Interval i = new Interval(2, 4);
        assertTrue(i.contains(2));
        assertTrue(i.contains(4));
        assertFalse(i.contains(1));
        assertFalse(i.contains(5));
    }
    @Test void overlapsTouchingEndpoint() {
        assertTrue(new Interval(1, 3).overlaps(new Interval(3, 5)));
        assertFalse(new Interval(1, 3).overlaps(new Interval(4, 5)));
    }
    @Test void intersection() {
        assertEquals(Optional.of(new Interval(3, 4)), new Interval(1, 4).intersection(new Interval(3, 9)));
        assertEquals(Optional.empty(), new Interval(1, 2).intersection(new Interval(3, 9)));
    }
    @Test void mergeAdjacentAndGap() {
        assertEquals(Optional.of(new Interval(1, 6)), new Interval(1, 3).merge(new Interval(4, 6)));
        assertEquals(Optional.empty(), new Interval(1, 3).merge(new Interval(5, 6)));
    }
    @Test void mergeAtIntMaxDoesNotOverflow() {
        Interval a = new Interval(0, Integer.MAX_VALUE);
        Interval b = new Interval(5, 10);
        assertEquals(Optional.of(a), a.merge(b));
        assertEquals(Optional.empty(), new Interval(Integer.MIN_VALUE, Integer.MIN_VALUE).merge(new Interval(Integer.MAX_VALUE, Integer.MAX_VALUE)));
    }
    @Test void mergeAllSortsAndCombines() {
        List<Interval> in = new ArrayList<>(List.of(
            new Interval(8, 10), new Interval(1, 3), new Interval(2, 5), new Interval(6, 7), new Interval(20, 21)));
        List<Interval> copy = new ArrayList<>(in);
        assertEquals(List.of(new Interval(1, 10), new Interval(20, 21)), Interval.mergeAll(in));
        assertEquals(copy, in);
    }
    @Test void mergeAllEmptyAndContained() {
        assertEquals(List.of(), Interval.mergeAll(List.of()));
        assertEquals(List.of(new Interval(1, 9)),
            Interval.mergeAll(List.of(new Interval(1, 9), new Interval(2, 3))));
    }
    @Test void equalsHashCodeToString() {
        assertEquals(new Interval(1, 2), new Interval(1, 2));
        assertEquals(new Interval(1, 2).hashCode(), new Interval(1, 2).hashCode());
        assertNotEquals(new Interval(1, 2), new Interval(1, 3));
        assertNotEquals(new Interval(1, 2), null);
        assertEquals("[3,7]", new Interval(3, 7).toString());
    }
}
