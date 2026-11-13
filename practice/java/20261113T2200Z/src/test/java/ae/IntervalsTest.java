package ae;

import static org.junit.jupiter.api.Assertions.*;

import ae.Intervals.Interval;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntervalsTest {
    private static Interval i(int a, int b) { return new Interval(a, b); }

    @Test void rejectsReversedInterval() {
        assertThrows(IllegalArgumentException.class, () -> i(5, 4));
    }

    @Test void mergeEmptyIsEmpty() {
        assertEquals(List.of(), Intervals.merge(List.of()));
    }

    @Test void mergeSortsAndJoinsOverlapping() {
        assertEquals(List.of(i(1, 6), i(8, 10)),
            Intervals.merge(List.of(i(8, 10), i(2, 4), i(1, 3), i(5, 6))));
    }

    @Test void mergeJoinsAdjacentIntegers() {
        assertEquals(List.of(i(1, 5)), Intervals.merge(List.of(i(4, 5), i(1, 3))));
    }

    @Test void mergeKeepsSeparatedWithGapOfOne() {
        assertEquals(List.of(i(1, 2), i(4, 5)), Intervals.merge(List.of(i(1, 2), i(4, 5))));
    }

    @Test void mergeAbsorbsContained() {
        assertEquals(List.of(i(0, 100)), Intervals.merge(List.of(i(0, 100), i(10, 20))));
    }

    @Test void mergeDoesNotMutateInputAndHandlesMaxValue() {
        List<Interval> in = new ArrayList<>(List.of(i(Integer.MAX_VALUE - 1, Integer.MAX_VALUE), i(0, 1)));
        List<Interval> copy = List.copyOf(in);
        assertEquals(List.of(i(0, 1), i(Integer.MAX_VALUE - 1, Integer.MAX_VALUE)), Intervals.merge(in));
        assertEquals(copy, in);
    }

    @Test void mergeAdjacentAtMaxValueDoesNotOverflow() {
        assertEquals(List.of(i(Integer.MAX_VALUE - 1, Integer.MAX_VALUE)),
            Intervals.merge(List.of(i(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1), i(Integer.MAX_VALUE, Integer.MAX_VALUE))));
    }

    @Test void mergeNullThrows() {
        assertThrows(NullPointerException.class, () -> Intervals.merge(null));
    }

    @Test void coveredLengthCountsPointsOnce() {
        assertEquals(8L, Intervals.coveredLength(List.of(i(1, 5), i(3, 6), i(10, 11))));
    }

    @Test void coveredLengthFullIntRangeExceedsInt() {
        assertEquals(4294967296L, Intervals.coveredLength(List.of(i(Integer.MIN_VALUE, Integer.MAX_VALUE))));
    }

    @Test void gapsInsideWindow() {
        assertEquals(List.of(i(0, 0), i(4, 4), i(7, 9)),
            Intervals.gaps(List.of(i(1, 3), i(5, 6)), i(0, 9)));
    }

    @Test void gapsIgnoreIntervalsOutsideAndClip() {
        assertEquals(List.of(i(12, 20)),
            Intervals.gaps(List.of(i(-5, 11), i(30, 40)), i(10, 20)));
    }

    @Test void gapsEmptyWhenFullyCovered() {
        assertEquals(List.of(), Intervals.gaps(List.of(i(0, 10)), i(2, 5)));
    }

    @Test void gapsWholeWindowWhenNoIntervals() {
        assertEquals(List.of(i(3, 7)), Intervals.gaps(List.of(), i(3, 7)));
    }
}
