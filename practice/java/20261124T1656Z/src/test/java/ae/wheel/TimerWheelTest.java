package ae.wheel;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TimerWheelTest {
    private final List<String> log = new ArrayList<>();

    private Runnable rec(TimerWheel w, String name) {
        return () -> log.add(name + "@" + w.now());
    }

    @Test
    void firesInDeadlineThenScheduleOrder() {
        TimerWheel w = new TimerWheel();
        w.schedule(5, rec(w, "a"));
        w.schedule(3, rec(w, "b"));
        w.schedule(5, rec(w, "c"));
        assertEquals(3, w.advanceTo(10));
        assertEquals(List.of("b@3", "a@5", "c@5"), log);
        assertEquals(10, w.now());
        assertEquals(0, w.size());
    }

    @Test
    void zeroDelayFiresOnAdvanceToCurrentTime() {
        TimerWheel w = new TimerWheel();
        w.schedule(0, rec(w, "z"));
        assertEquals(1, w.size());
        assertEquals(1, w.advanceTo(0));
        assertEquals(List.of("z@0"), log);
    }

    @Test
    void doesNotFireBeforeDeadline() {
        TimerWheel w = new TimerWheel();
        TimerWheel.Handle h = w.schedule(10, rec(w, "x"));
        assertEquals(0, w.advanceTo(9));
        assertEquals(9, w.now());
        assertEquals(1, w.size());
        assertFalse(h.isDone());
        assertEquals(10, h.deadline());
        assertEquals(1, w.advanceTo(10));
        assertTrue(h.isDone());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 63, 64, 100, 4095, 4096, 262143, 262144, 16777213, 16777216, 1L << 30})
    void boundaryStartTimesAndDelaysFireExactlyAtDeadline(long start) {
        long[] delays = {0, 1, 2, 63, 64, 65, 4095, 4096, 4097, 262143, 262144, 262145,
                16777215, 16777216, 16777217, (1L << 30) + 7};
        for (long delay : delays) {
            log.clear();
            TimerWheel w = new TimerWheel(start);
            w.schedule(delay, rec(w, "t"));
            long d = start + delay;
            if (delay > 0) {
                assertEquals(0, w.advanceTo(d - 1), "start " + start + " delay " + delay);
                assertEquals(List.of(), log);
            }
            assertEquals(1, w.advanceTo(d), "start " + start + " delay " + delay);
            assertEquals(List.of("t@" + d), log);
        }
    }

    @Test
    void cascadedTimerKeepsItsScheduleOrderAgainstDirectOne() {
        TimerWheel w = new TimerWheel();
        w.schedule(4100, rec(w, "A")); // level 2 now, cascades at 4096 (seq 0)
        w.advanceTo(4000);
        w.schedule(100, rec(w, "B")); // same deadline 4100, linked directly (seq 1)
        w.advanceTo(5000);
        assertEquals(List.of("A@4100", "B@4100"), log);
    }

    @Test
    void cancelPreventsFiringAndIsOnlyTrueOnce() {
        TimerWheel w = new TimerWheel();
        TimerWheel.Handle near = w.schedule(5, rec(w, "near"));
        TimerWheel.Handle far = w.schedule(1L << 40, rec(w, "far")); // overflow heap
        TimerWheel.Handle mid = w.schedule(300000, rec(w, "mid"));
        assertEquals(3, w.size());
        assertTrue(near.cancel());
        assertFalse(near.cancel());
        assertTrue(far.cancel());
        assertEquals(1, w.size());
        assertTrue(near.isDone());
        assertEquals(1, w.advanceTo(1L << 41));
        assertEquals(List.of("mid@300000"), log);
        assertFalse(mid.cancel(), "already fired");
        assertEquals(0, w.size());
    }

    @Test
    void callbackCanCancelAQueuedTimerOfTheSameBatch() {
        TimerWheel w = new TimerWheel();
        TimerWheel.Handle[] victim = new TimerWheel.Handle[1];
        w.schedule(7, () -> {
            log.add("first");
            assertTrue(victim[0].cancel());
        });
        victim[0] = w.schedule(7, rec(w, "victim"));
        w.schedule(7, rec(w, "last"));
        assertEquals(2, w.advanceTo(7));
        assertEquals(List.of("first", "last@7"), log);
        assertEquals(0, w.size());
    }

    @Test
    void periodicIsFixedRateAndCatchesUp() {
        TimerWheel w = new TimerWheel();
        TimerWheel.Handle h = w.scheduleAtFixedRate(10, 10, rec(w, "p"));
        assertEquals(3, w.advanceTo(35));
        assertEquals(List.of("p@10", "p@20", "p@30"), log);
        assertEquals(40, h.deadline());
        assertEquals(1, w.size());
        log.clear();
        assertEquals(97, w.advanceTo(1000)); // 40,50,...,1000 inclusive: (1000-40)/10+1
        assertEquals("p@40", log.get(0));
        assertEquals("p@1000", log.get(96));
        assertEquals(1010, h.deadline());
        assertTrue(h.cancel());
        assertTrue(h.isDone());
        assertEquals(0, w.size());
        assertEquals(0, w.advanceTo(5000));
    }

    @Test
    void periodicCancelInsideItsOwnCallbackStopsRearm() {
        TimerWheel w = new TimerWheel();
        TimerWheel.Handle[] self = new TimerWheel.Handle[1];
        int[] runs = {0};
        self[0] = w.scheduleAtFixedRate(5, 5, () -> {
            if (++runs[0] == 3) {
                assertTrue(self[0].cancel());
            }
        });
        assertEquals(3, w.advanceTo(100));
        assertTrue(self[0].isDone());
        assertEquals(0, w.size());
    }

    @Test
    void oneShotCannotBeCancelledFromItsOwnCallback() {
        TimerWheel w = new TimerWheel();
        TimerWheel.Handle[] self = new TimerWheel.Handle[1];
        boolean[] result = {true};
        self[0] = w.schedule(1, () -> result[0] = self[0].cancel());
        w.advanceTo(1);
        assertFalse(result[0]);
        assertTrue(self[0].isDone());
    }

    @Test
    void rearmedPeriodicGetsFreshSequenceNumber() {
        TimerWheel w = new TimerWheel();
        w.scheduleAtFixedRate(10, 10, rec(w, "p")); // seq 0
        w.schedule(20, rec(w, "o")); // seq 1, deadline 20
        w.advanceTo(20); // p re-armed at t=10 with seq 2 for deadline 20
        assertEquals(List.of("p@10", "o@20", "p@20"), log);
    }

    @Test
    void periodicEndsWhenNextDeadlineOverflows() {
        TimerWheel w = new TimerWheel(Long.MAX_VALUE - 100);
        TimerWheel.Handle h = w.scheduleAtFixedRate(60, 60, rec(w, "p"));
        assertEquals(1, w.advanceTo(Long.MAX_VALUE));
        assertTrue(h.isDone());
        assertEquals(0, w.size());
        assertEquals(List.of("p@" + (Long.MAX_VALUE - 40)), log);
    }

    @Test
    void zeroDelayScheduledByCallbackFiresInSameAdvanceAfterCurrentBatch() {
        TimerWheel w = new TimerWheel();
        w.schedule(5, () -> {
            log.add("A");
            w.schedule(0, rec(w, "C"));
            w.schedule(3, rec(w, "D"));
        });
        w.schedule(5, rec(w, "B"));
        assertEquals(4, w.advanceTo(10));
        assertEquals(List.of("A", "B@5", "C@5", "D@8"), log);
    }

    @Test
    void callbackThatSchedulesBeyondTargetLeavesItPending() {
        TimerWheel w = new TimerWheel();
        w.schedule(5, () -> w.schedule(10, rec(w, "late")));
        assertEquals(1, w.advanceTo(12));
        assertEquals(1, w.size());
        assertEquals(OptionalLong.of(15), w.nextDeadline());
        assertEquals(1, w.advanceTo(15));
        assertEquals(List.of("late@15"), log);
    }

    @Test
    void advanceFromInsideACallbackIsRejected() {
        TimerWheel w = new TimerWheel();
        w.schedule(1, () -> assertThrows(IllegalStateException.class, () -> w.advanceTo(50)));
        assertEquals(1, w.advanceTo(2));
        assertEquals(2, w.now());
    }

    @Test
    void failingCallbacksDoNotStopTheRun() {
        TimerWheel w = new TimerWheel();
        w.schedule(1, () -> {
            throw new IllegalArgumentException("boom1");
        });
        w.schedule(2, rec(w, "ok"));
        w.schedule(3, () -> {
            throw new UnsupportedOperationException("boom2");
        });
        w.schedule(4, rec(w, "ok2"));
        w.schedule(50, rec(w, "later"));
        TimerWheel.TimerFailure f = assertThrows(TimerWheel.TimerFailure.class, () -> w.advanceTo(10));
        assertEquals("boom1", f.getCause().getMessage());
        assertEquals(1, f.getSuppressed().length);
        assertEquals("boom2", f.getSuppressed()[0].getMessage());
        assertEquals(List.of("ok@2", "ok2@4"), log);
        assertEquals(10, w.now());
        assertEquals(1, w.size());
    }

    @Test
    void failingPeriodicCallbackIsStillRearmed() {
        TimerWheel w = new TimerWheel();
        int[] runs = {0};
        w.scheduleAtFixedRate(1, 1, () -> {
            runs[0]++;
            throw new IllegalStateException("x");
        });
        assertThrows(TimerWheel.TimerFailure.class, () -> w.advanceTo(3));
        assertEquals(3, runs[0]);
        assertEquals(1, w.size());
    }

    @Test
    void validationRejectsBadArgumentsWithoutSideEffects() {
        TimerWheel w = new TimerWheel();
        w.advanceTo(5);
        Runnable r = () -> { };
        assertThrows(IllegalArgumentException.class, () -> w.schedule(-1, r));
        assertThrows(IllegalArgumentException.class, () -> w.schedule(Long.MAX_VALUE, r));
        assertThrows(IllegalArgumentException.class, () -> w.scheduleAtFixedRate(1, 0, r));
        assertThrows(IllegalArgumentException.class, () -> w.scheduleAtFixedRate(-1, 1, r));
        assertThrows(NullPointerException.class, () -> w.schedule(1, null));
        assertThrows(IllegalArgumentException.class, () -> w.advanceTo(4));
        assertThrows(IllegalArgumentException.class, () -> new TimerWheel(-1));
        assertEquals(0, w.size());
        assertEquals(5, w.now());
        assertEquals(OptionalLong.empty(), w.nextDeadline());
        TimerWheel.Handle edge = w.schedule(Long.MAX_VALUE - 5, r);
        assertEquals(Long.MAX_VALUE, edge.deadline());
        assertEquals(1, w.size());
    }

    @Test
    void nextDeadlineIsTheEarliestAcrossLevelsAndOverflow() {
        TimerWheel w = new TimerWheel();
        assertEquals(OptionalLong.empty(), w.nextDeadline());
        TimerWheel.Handle far = w.schedule(1L << 35, rec(w, "far"));
        assertEquals(OptionalLong.of(1L << 35), w.nextDeadline());
        TimerWheel.Handle l3 = w.schedule(300000, rec(w, "l3"));
        assertEquals(OptionalLong.of(300000), w.nextDeadline());
        w.schedule(70000, rec(w, "l2"));
        TimerWheel.Handle l1 = w.schedule(200, rec(w, "l1"));
        w.schedule(4000, rec(w, "l1b"));
        assertEquals(OptionalLong.of(200), w.nextDeadline());
        l1.cancel();
        assertEquals(OptionalLong.of(4000), w.nextDeadline());
        w.advanceTo(4000);
        assertEquals(OptionalLong.of(70000), w.nextDeadline());
        w.advanceTo(70000);
        assertEquals(OptionalLong.of(300000), w.nextDeadline());
        l3.cancel();
        assertEquals(OptionalLong.of(1L << 35), w.nextDeadline());
        far.cancel();
        assertEquals(OptionalLong.empty(), w.nextDeadline());
    }

    @Test
    void sameLevelTimersInOneSlotKeepTheMinimumForNextDeadline() {
        TimerWheel w = new TimerWheel();
        w.schedule(4200, rec(w, "b")); // level 2 slot 1
        w.schedule(4100, rec(w, "a")); // same slot, smaller deadline
        assertEquals(OptionalLong.of(4100), w.nextDeadline());
    }

    @Test
    void idleJumpsDoNotWalkEveryTick() {
        TimerWheel w = new TimerWheel();
        w.schedule(1_000_000, rec(w, "a"));
        w.schedule(1_000_000_000, rec(w, "b"));
        w.schedule(1_000_000_000_000L, rec(w, "c"));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> w.advanceTo(2_000_000_000_000L));
        assertEquals(List.of("a@1000000", "b@1000000000", "c@1000000000000"), log);
        assertEquals(2_000_000_000_000L, w.now());
    }

    @Test
    void manyTimersFireInSortedOrderQuickly() {
        TimerWheel w = new TimerWheel();
        int n = 200_000;
        long[] deadlines = new long[n];
        java.util.Random rnd = new java.util.Random(7);
        long[] fireTimes = new long[n];
        int[] count = {0};
        for (int i = 0; i < n; i++) {
            long delay = rnd.nextInt(5) == 0 ? rnd.nextInt(1 << 30) : rnd.nextInt(100_000);
            deadlines[i] = delay;
            w.schedule(delay, () -> fireTimes[count[0]++] = w.now());
        }
        assertEquals(n, w.size());
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> w.advanceTo(1L << 31));
        assertEquals(n, count[0]);
        java.util.Arrays.sort(deadlines);
        assertArrayEquals(deadlines, fireTimes);
    }

    @Test
    void invariantCheckerRejectsACorruptedWheel() {
        TimerWheel w = new TimerWheel();
        w.schedule(100, rec(w, "x"));
        w.checkInvariants();
        w.corruptOccupancyForTest();
        assertThrows(IllegalStateException.class, w::checkInvariants);
    }
}
