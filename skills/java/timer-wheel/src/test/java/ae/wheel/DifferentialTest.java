package ae.wheel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the same seeded program against the wheel and a PriorityQueue oracle and compares everything. */
class DifferentialTest {

    /** What the driver needs from a timer service. */
    interface Timers {
        Object schedule(long delay, Runnable r);

        Object periodic(long initial, long period, Runnable r);

        boolean cancel(Object handle);

        long now();

        void advanceTo(long target);

        int size();

        OptionalLong next();

        void check();
    }

    static final class WheelTimers implements Timers {
        final TimerWheel w;

        WheelTimers(long start) {
            w = new TimerWheel(start);
        }

        public Object schedule(long d, Runnable r) {
            return w.schedule(d, r);
        }

        public Object periodic(long i, long p, Runnable r) {
            return w.scheduleAtFixedRate(i, p, r);
        }

        public boolean cancel(Object h) {
            return ((TimerWheel.Handle) h).cancel();
        }

        public long now() {
            return w.now();
        }

        public void advanceTo(long t) {
            w.advanceTo(t);
        }

        public int size() {
            return w.size();
        }

        public OptionalLong next() {
            return w.nextDeadline();
        }

        public void check() {
            w.checkInvariants();
        }
    }

    /** Obviously-correct model: one heap ordered by (deadline, seq). */
    static final class Oracle implements Timers {
        static final class T {
            long deadline;
            long seq;
            long period;
            Runnable task;
            int state; // 0 pending, 1 running, 2 done
        }

        final PriorityQueue<T> heap = new PriorityQueue<>((a, b) ->
                a.deadline != b.deadline ? Long.compare(a.deadline, b.deadline) : Long.compare(a.seq, b.seq));
        long now;
        long seq;
        int live;

        Oracle(long start) {
            now = start;
        }

        private T add(long delay, long period, Runnable r) {
            T t = new T();
            t.deadline = now + delay;
            t.seq = seq++;
            t.period = period;
            t.task = r;
            heap.add(t);
            live++;
            return t;
        }

        public Object schedule(long d, Runnable r) {
            return add(d, 0, r);
        }

        public Object periodic(long i, long p, Runnable r) {
            return add(i, p, r);
        }

        public boolean cancel(Object h) {
            T t = (T) h;
            if (t.state == 0) {
                t.state = 2;
                heap.remove(t);
                live--;
                return true;
            }
            if (t.state == 1 && t.period > 0) {
                t.state = 2;
                return true;
            }
            return false;
        }

        public long now() {
            return now;
        }

        public void advanceTo(long target) {
            while (!heap.isEmpty() && heap.peek().deadline <= target) {
                T t = heap.poll();
                live--;
                now = Math.max(now, t.deadline);
                t.state = 1;
                t.task.run();
                if (t.period > 0 && t.state == 1) {
                    long nd = t.deadline + t.period;
                    if (nd < 0) {
                        t.state = 2;
                    } else {
                        t.deadline = nd;
                        t.seq = seq++;
                        t.state = 0;
                        heap.add(t);
                        live++;
                    }
                } else if (t.state == 1) {
                    t.state = 2;
                }
            }
            now = target;
        }

        public int size() {
            return live;
        }

        public OptionalLong next() {
            return heap.isEmpty() ? OptionalLong.empty() : OptionalLong.of(heap.peek().deadline);
        }

        public void check() { }
    }

    private static final long[] DELAYS = {0, 0, 1, 2, 5, 63, 64, 65, 200, 4095, 4096, 4097, 70000, 262143, 262144,
            262145, 16777215, 16777216, 16777217, 1L << 25, 1L << 30, 1L << 33};
    private static final long[] STEPS = {0, 1, 1, 7, 63, 64, 65, 100, 4096, 10000, 262144, 1 << 24, 1 << 26};
    private static final long[] PERIODS = {64, 1000, 4096, 100000, 1 << 20};

    /** Runs one seeded program; returns the full event log. */
    static List<String> run(Timers t, long seed, long start, int rounds) {
        Random rnd = new Random(seed);
        List<String> log = new ArrayList<>();
        List<Object> handles = new ArrayList<>();
        int[] periodics = {0};
        long limit = start + (1L << 34);
        class Maker {
            void make() {
                int id = handles.size();
                long delay = rnd.nextInt(4) == 0 ? rnd.nextInt(1 << 20) : DELAYS[rnd.nextInt(DELAYS.length)];
                int kind = rnd.nextInt(10); // 0-2: reacts by scheduling a child, 3: reacts by cancelling
                Runnable r = () -> {
                    log.add(id + "@" + t.now());
                    if (kind <= 2 && handles.size() < 3000) {
                        make();
                    } else if (kind == 3) {
                        log.add("cancel " + t.cancel(handles.get(rnd.nextInt(handles.size()))));
                    }
                };
                if (rnd.nextInt(25) == 0 && periodics[0] < 3) {
                    periodics[0]++;
                    int[] runs = {0};
                    int stopAfter = 1 + rnd.nextInt(40);
                    Object[] self = new Object[1];
                    long period = PERIODS[rnd.nextInt(PERIODS.length)];
                    self[0] = t.periodic(delay, period, () -> {
                        log.add("p" + id + "@" + t.now());
                        if (++runs[0] == stopAfter) {
                            log.add("pcancel " + t.cancel(self[0]));
                        }
                    });
                    handles.add(self[0]);
                } else {
                    handles.add(t.schedule(delay, r));
                }
            }
        }
        Maker maker = new Maker();
        for (int round = 0; round < rounds; round++) {
            int ops = rnd.nextInt(6);
            for (int i = 0; i < ops; i++) {
                if (rnd.nextInt(5) == 0 && !handles.isEmpty()) {
                    log.add("c " + t.cancel(handles.get(rnd.nextInt(handles.size()))));
                } else {
                    maker.make();
                }
            }
            long target = Math.min(limit, t.now() + STEPS[rnd.nextInt(STEPS.length)]);
            t.advanceTo(target);
            t.check();
            log.add("= now " + t.now() + " size " + t.size() + " next " + t.next());
        }
        t.advanceTo(limit);
        log.add("= end size " + t.size() + " next " + t.next());
        return log;
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 63, 100, 4000, 16777000})
    void wheelMatchesOracleOnRandomPrograms(long start) {
        for (long seed = 1; seed <= 25; seed++) {
            List<String> expected = run(new Oracle(start), seed * 31 + start, start, 60);
            List<String> actual = run(new WheelTimers(start), seed * 31 + start, start, 60);
            assertEquals(expected, actual, "seed " + seed + " start " + start);
            assertTrue(expected.size() > 30, "program must do something, seed " + seed);
        }
    }

    @Test
    void randomProgramsActuallyExerciseEveryLevelAndCancellation() {
        int fired = 0;
        int cancels = 0;
        int nested = 0;
        for (long seed = 1; seed <= 25; seed++) {
            for (String line : run(new Oracle(0), seed * 31, 0, 60)) {
                if (line.contains("@")) {
                    fired++;
                }
                if (line.startsWith("c true") || line.startsWith("cancel true")) {
                    cancels++;
                }
                if (line.startsWith("cancel")) {
                    nested++;
                }
            }
        }
        assertTrue(fired > 500, "fired " + fired);
        assertTrue(cancels > 20, "successful cancels " + cancels);
        assertTrue(nested > 5, "cancels from callbacks " + nested);
    }

    @Test
    void oracleItselfAgreesWithATrivialHandWrittenTrace() {
        Oracle o = new Oracle(0);
        List<String> out = new ArrayList<>();
        o.schedule(5, () -> out.add("a" + o.now()));
        o.schedule(2, () -> out.add("b" + o.now()));
        o.advanceTo(5);
        assertEquals(List.of("b2", "a5"), out);
    }
}
