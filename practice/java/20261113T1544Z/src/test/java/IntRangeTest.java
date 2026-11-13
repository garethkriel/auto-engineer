import ae.IntRange;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntRangeTest {
    @Test void rejectsReversedBounds() { assertThrows(IllegalArgumentException.class, () -> new IntRange(5, 4)); }
    @Test void allowsEmpty() { assertTrue(IntRange.of(3, 3).isEmpty()); }
    @Test void lengthDoesNotOverflow() { assertEquals(4294967295L, IntRange.of(Integer.MIN_VALUE, Integer.MAX_VALUE).length()); }
    @Test void containsIsHalfOpen() {
        IntRange r = IntRange.of(1, 5);
        assertTrue(r.contains(1)); assertTrue(r.contains(4));
        assertFalse(r.contains(5)); assertFalse(r.contains(0));
    }
    @Test void overlapsWhenSharingAnInteger() { assertTrue(IntRange.of(1, 5).overlaps(IntRange.of(4, 8))); }
    @Test void adjacentDoNotOverlap() { assertFalse(IntRange.of(1, 5).overlaps(IntRange.of(5, 8))); }
    @Test void emptyNeverOverlaps() { assertFalse(IntRange.of(1, 5).overlaps(IntRange.of(3, 3))); }
    @Test void mergeOverlapping() { assertEquals(IntRange.of(1, 8), IntRange.of(1, 5).merge(IntRange.of(4, 8))); }
    @Test void mergeAdjacent() { assertEquals(IntRange.of(1, 8), IntRange.of(5, 8).merge(IntRange.of(1, 5))); }
    @Test void mergeWithGapFails() { assertThrows(IllegalArgumentException.class, () -> IntRange.of(1, 3).merge(IntRange.of(4, 8))); }
    @Test void mergeWithEmptyReturnsOther() {
        assertEquals(IntRange.of(1, 3), IntRange.of(1, 3).merge(IntRange.of(100, 100)));
        assertEquals(IntRange.of(1, 3), IntRange.of(100, 100).merge(IntRange.of(1, 3)));
    }
    @Test void containedRangeMerge() { assertEquals(IntRange.of(0, 10), IntRange.of(0, 10).merge(IntRange.of(2, 3))); }
    @Test void equalsAndHashCode() {
        assertEquals(IntRange.of(1, 2), IntRange.of(1, 2));
        assertEquals(IntRange.of(1, 2).hashCode(), IntRange.of(1, 2).hashCode());
        assertNotEquals(IntRange.of(1, 2), IntRange.of(1, 3));
        assertNotEquals(IntRange.of(1, 1), IntRange.of(2, 2));
        assertNotEquals(IntRange.of(1, 2), null);
        assertNotEquals(IntRange.of(1, 2), "[1, 2)");
    }
    @Test void toStringFormat() { assertEquals("[1, 5)", IntRange.of(1, 5).toString()); }
    @Test void nullArgsThrow() {
        assertThrows(NullPointerException.class, () -> IntRange.of(1, 2).overlaps(null));
        assertThrows(NullPointerException.class, () -> IntRange.of(1, 2).merge(null));
    }
}
