package ae.stream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WindowEngineTest {

    private static Event ev(String key, long t, long v) {
        return new Event(key, t, v);
    }

    private static Result res(String key, long s, long e, long count, long sum, long min, long max, Kind kind) {
        return new Result(key, new Window(s, e), new Agg(count, sum, min, max), kind);
    }

    @Test
    void tumblingFiresWhenWatermarkReachesEnd() {
        WindowEngine w = new WindowEngine(new WindowSpec.Tumbling(10), 0, 0);
        assertEquals(List.of(), w.process(ev("a", 1, 5)));
        assertEquals(List.of(), w.process(ev("a", 3, 7)));
        // ts 12 -> watermark 12 >= 10: window [0,10) fires; [10,20) stays open
        assertEquals(List.of(res("a", 0, 10, 2, 12, 5, 7, Kind.ON_TIME)), w.process(ev("a", 12, 1)));
        assertEquals(List.of(res("a", 10, 20, 1, 1, 1, 1, Kind.ON_TIME)), w.flush());
        assertEquals(List.of(), w.flush());
    }

    @Test
    void windowEndIsExclusiveAndWatermarkEqualToEndFires() {
        WindowEngine w = new WindowEngine(new WindowSpec.Tumbling(10), 0, 0);
        w.process(ev("a", 9, 1));
        // time 10 belongs to [10,20); watermark becomes exactly 10 == end of [0,10)
        assertEquals(List.of(res("a", 0, 10, 1, 1, 1, 1, Kind.ON_TIME)), w.process(ev("a", 10, 2)));
        assertEquals(1, w.openWindowCount());
    }

    @Test
    void outOfOrderBoundDelaysFiring() {
        WindowEngine w = new WindowEngine(new WindowSpec.Tumbling(10), 5, 0);
        w.process(ev("a", 1, 1));
        assertEquals(List.of(), w.process(ev("a", 12, 1)));   // wm = 7
        assertEquals(7, w.watermark());
        assertEquals(List.of(), w.process(ev("a", 9, 4)));    // late by arrival, still on time
        // ts 16 -> wm 11 >= 10: [0,10) now holds ts 1 and ts 9
        assertEquals(List.of(res("a", 0, 10, 2, 5, 1, 4, Kind.ON_TIME)), w.process(ev("a", 16, 1)));
    }

    @Test
    void watermarkNeverMovesBackwards() {
        WindowEngine w = new WindowEngine(new WindowSpec.Tumbling(10), 0, 100);
        w.process(ev("a", 50, 1));
        w.process(ev("a", 20, 1));
        assertEquals(50, w.watermark());
    }

    @Test
    void lateEventWithinLatenessEmitsUpdateThenIsDroppedAfterPurge() {
        WindowEngine w = new WindowEngine(new WindowSpec.Tumbling(10), 0, 5);
        w.process(ev("a", 1, 1));
        assertEquals(1, w.process(ev("a", 12, 1)).size());               // fires [0,10), wm 12
        // end 10 + lateness 5 = 15 > 12: accepted, refined result emitted at once
        assertEquals(List.of(res("a", 0, 10, 2, 5, 1, 4, Kind.LATE_UPDATE)), w.process(ev("a", 4, 4)));
        // ts 20: wm 20; [10,20) fires, [0,10) purged since 15 <= 20
        assertEquals(List.of(res("a", 10, 20, 1, 1, 1, 1, Kind.ON_TIME)), w.process(ev("a", 20, 9)));
        assertEquals(List.of(), w.process(ev("a", 3, 100)));
        assertEquals(List.of(new Dropped(ev("a", 3, 100), new Window(0, 10))), w.dropped());
    }

    @Test
    void latenessBoundaryEqualDroppedOneLessAccepted() {
        // size 10, lateness 5: window [0,10) is dead exactly when wm >= 15
        WindowEngine w = new WindowEngine(new WindowSpec.Tumbling(10), 0, 5);
        w.process(ev("a", 14, 1));                                       // wm 14
        assertEquals(1, w.process(ev("a", 3, 1)).size());                // 15 > 14 -> accepted (late update)
        assertEquals(List.of(), w.dropped());
        w.process(ev("a", 15, 1));                                       // wm 15 -> [0,10) purged
        w.process(ev("a", 3, 1));
        assertEquals(1, w.dropped().size());
        assertEquals(new Window(0, 10), w.dropped().get(0).window());
    }

    @Test
    @SuppressWarnings("unused")
    void slidingAssignsEveryOverlappingWindowIncludingNegativeTimes() {
        // size 10, slide 5: window starts are multiples of 5
        long[][] table = {
            {7, 0, 5},      // ts 7 -> [0,10) and [5,15)
            {5, 0, 5},
            {10, 5, 10},    // ts 10 -> [5,15) and [10,20)
            {0, -5, 0},     // ts 0 -> [-5,5) and [0,10)
            {-1, -10, -5},  // ts -1 -> [-10,0) and [-5,5)
            {-5, -10, -5},  // ts -5 -> [-10,0) and [-5,5)
        };
        for (long[] row : table) {
            WindowEngine w = new WindowEngine(new WindowSpec.Sliding(10, 5), 0, 0);
            w.process(ev("k", row[0], 1));
            List<Result> out = w.flush();
            assertEquals(List.of(
                res("k", row[1], row[1] + 10, 1, 1, 1, 1, Kind.ON_TIME),
                res("k", row[2], row[2] + 10, 1, 1, 1, 1, Kind.ON_TIME)), out, "ts " + row[0]);
        }
    }

    @Test
    void slidingEqualSizeAndSlideBehavesLikeTumbling() {
        WindowEngine a = new WindowEngine(new WindowSpec.Sliding(10, 10), 0, 0);
        WindowEngine b = new WindowEngine(new WindowSpec.Tumbling(10), 0, 0);
        for (long t : new long[] {3, 14, 9, -4, 27}) {
            assertEquals(b.process(ev("k", t, t)), a.process(ev("k", t, t)));
        }
        assertEquals(b.flush(), a.flush());
    }

    @Test
    void sessionMergesOverlappingAndTouchingWindows() {
        WindowEngine w = new WindowEngine(new WindowSpec.Session(5), 100, 100);
        w.process(ev("a", 1, 1));      // [1,6)
        w.process(ev("a", 3, 2));      // [3,8) overlaps -> [1,8)
        w.process(ev("a", 8, 4));      // [8,13) touches 8 -> [1,13)
        w.process(ev("a", 30, 8));     // separate [30,35)
        assertEquals(List.of(
            res("a", 1, 13, 3, 7, 1, 4, Kind.ON_TIME),
            res("a", 30, 35, 1, 8, 8, 8, Kind.ON_TIME)), w.flush());
    }

    @Test
    void sessionGapOfExactlyGapDoesNotTouchButGapMinusOneDoes() {
        WindowEngine w = new WindowEngine(new WindowSpec.Session(5), 100, 100);
        w.process(ev("a", 0, 1));      // [0,5)
        w.process(ev("a", 5, 1));      // [5,10) touches [0,5) -> merged
        w.process(ev("b", 0, 1));      // [0,5)
        w.process(ev("b", 6, 1));      // [6,11) leaves 1 unit hole -> separate
        assertEquals(List.of(
            res("b", 0, 5, 1, 1, 1, 1, Kind.ON_TIME),
            res("a", 0, 10, 2, 2, 1, 1, Kind.ON_TIME),
            res("b", 6, 11, 1, 1, 1, 1, Kind.ON_TIME)), w.flush());
    }

    @Test
    void sessionBridgingEventMergesTwoWindows() {
        WindowEngine w = new WindowEngine(new WindowSpec.Session(5), 100, 100);
        w.process(ev("a", 0, 1));      // [0,5)
        w.process(ev("a", 10, 2));     // [10,15)
        w.process(ev("a", 5, 4));      // [5,10) touches both
        assertEquals(List.of(res("a", 0, 15, 3, 7, 1, 4, Kind.ON_TIME)), w.flush());
    }

    @Test
    void sessionLateEventMergingIntoFiredWindowEmitsMergedUpdate() {
        WindowEngine w = new WindowEngine(new WindowSpec.Session(5), 0, 50);
        w.process(ev("a", 1, 1));                                        // [1,6)
        assertEquals(List.of(res("a", 1, 6, 1, 1, 1, 1, Kind.ON_TIME)), w.process(ev("a", 20, 1)));
        // ts 4 -> proto [4,9) overlaps fired [1,6) -> merged [1,9) (end 9 <= wm 20) -> LATE_UPDATE
        assertEquals(List.of(res("a", 1, 9, 2, 5, 1, 4, Kind.LATE_UPDATE)), w.process(ev("a", 4, 4)));
    }

    @Test
    void sessionMergedWindowThatReachesPastWatermarkFiresAgainLater() {
        WindowEngine w = new WindowEngine(new WindowSpec.Session(10), 0, 50);
        w.process(ev("a", 0, 1));                                        // [0,10)
        assertEquals(1, w.process(ev("a", 12, 1)).size());               // wm 12 fires [0,10); [12,22) open
        // ts 11 -> proto [11,21) touches fired [0,10)? 11 > 10 no; overlaps [12,22) -> merged [11,22)
        assertEquals(List.of(), w.process(ev("a", 11, 1)));
        // ts 9 -> proto [9,19): touches [0,10) and overlaps [11,22) -> one window [0,22), end 22 > wm 12: silent
        assertEquals(List.of(), w.process(ev("a", 9, 1)));
        assertEquals(List.of(res("a", 0, 22, 4, 4, 1, 1, Kind.ON_TIME)), w.flush());
    }

    @Test
    void sessionEventTooLateIsDropped() {
        WindowEngine w = new WindowEngine(new WindowSpec.Session(5), 0, 0);
        w.process(ev("a", 100, 1));
        w.process(ev("a", 10, 1));     // proto [10,15), 15 + 0 <= 100
        assertEquals(List.of(new Dropped(ev("a", 10, 1), new Window(10, 15))), w.dropped());
    }

    @Test
    void keysAreIndependentButShareTheWatermark() {
        WindowEngine w = new WindowEngine(new WindowSpec.Tumbling(10), 0, 0);
        w.process(ev("a", 1, 1));
        w.process(ev("b", 2, 10));
        List<Result> out = w.process(ev("c", 25, 1));                    // wm 25 fires both
        assertEquals(List.of(
            res("a", 0, 10, 1, 1, 1, 1, Kind.ON_TIME),
            res("b", 0, 10, 1, 10, 10, 10, Kind.ON_TIME)), out);
    }

    @Test
    void afterFlushEveryEventIsDropped() {
        WindowEngine w = new WindowEngine(new WindowSpec.Tumbling(10), 0, 1_000);
        w.flush();
        assertEquals(List.of(), w.process(ev("a", 1, 1)));
        assertEquals(1, w.dropped().size());
        assertEquals(0, w.openWindowCount());
    }

    @Test
    void constructorAndArgumentValidation() {
        assertThrows(IllegalArgumentException.class, () -> new WindowSpec.Tumbling(0));
        assertThrows(IllegalArgumentException.class, () -> new WindowSpec.Sliding(5, 6));
        assertThrows(IllegalArgumentException.class, () -> new WindowSpec.Sliding(5, 0));
        assertThrows(IllegalArgumentException.class, () -> new WindowSpec.Session(-1));
        assertThrows(IllegalArgumentException.class, () -> new Window(3, 3));
        WindowSpec t = new WindowSpec.Tumbling(10);
        assertThrows(IllegalArgumentException.class, () -> new WindowEngine(t, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new WindowEngine(t, 0, -1));
        assertThrows(NullPointerException.class, () -> new WindowEngine(null, 0, 0));
        assertThrows(NullPointerException.class, () -> new WindowEngine(t, 0, 0).process(null));
        assertThrows(NullPointerException.class, () -> new Event(null, 1, 1));
    }

    @Test
    void overflowingEventsFailWithoutChangingState() {
        WindowEngine w = new WindowEngine(new WindowSpec.Tumbling(10), 0, 0);
        w.process(ev("a", 5, Long.MAX_VALUE));
        byte[] before = w.snapshot();
        // sum overflow in an existing window
        assertThrows(ArithmeticException.class, () -> w.process(ev("a", 6, 1)));
        // window end overflow
        assertThrows(ArithmeticException.class, () -> w.process(ev("a", Long.MAX_VALUE - 2, 1)));
        assertArrayEquals(before, w.snapshot());
        assertEquals(0, w.dropped().size());
        assertEquals(5, w.watermark());
    }

    @Test
    void sessionSumOverflowLeavesExistingWindowsIntact() {
        WindowEngine w = new WindowEngine(new WindowSpec.Session(5), 100, 100);
        w.process(ev("a", 0, Long.MAX_VALUE));
        w.process(ev("a", 20, 1));
        byte[] before = w.snapshot();
        assertThrows(ArithmeticException.class, () -> w.process(ev("a", 3, 1)));
        assertArrayEquals(before, w.snapshot());
    }

    @Test
    void extremeTimesWorkWhenWindowsFitAndFailCleanlyWhenTheyDoNot() {
        WindowEngine w = new WindowEngine(new WindowSpec.Tumbling(10), 0, 0);
        // -9223372036854775800 is a multiple of 10, so its window is [t, t+10)
        w.process(ev("a", -9223372036854775800L, 1));
        assertEquals(List.of(new Result("a", new Window(-9223372036854775800L, -9223372036854775790L),
            Agg.of(1), Kind.ON_TIME)), w.flush());
        WindowEngine v = new WindowEngine(new WindowSpec.Tumbling(10), 0, 0);
        byte[] before = v.snapshot();
        // Long.MIN_VALUE mod 10 == 2, so the aligned start would underflow
        assertThrows(ArithmeticException.class, () -> v.process(ev("a", Long.MIN_VALUE, 1)));
        assertArrayEquals(before, v.snapshot());
    }
}
