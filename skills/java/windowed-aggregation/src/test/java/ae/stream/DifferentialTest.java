package ae.stream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class DifferentialTest {

    /** Near-sorted event stream: base time grows, each event is jittered backwards by up to {@code jitter}. */
    private static List<Event> stream(Random rnd, int n, int keys, int jitter) {
        List<Event> out = new ArrayList<>();
        long base = rnd.nextInt(20) - 10;
        for (int i = 0; i < n; i++) {
            base += rnd.nextInt(6);
            long t = base - rnd.nextInt(jitter + 1);
            out.add(new Event("k" + rnd.nextInt(keys), t, rnd.nextInt(100) - 20));
        }
        return out;
    }

    private static List<Window> windows(WindowSpec spec, long t) {
        List<Window> ws = new ArrayList<>();
        long size = spec instanceof WindowSpec.Tumbling tm ? tm.size() : ((WindowSpec.Sliding) spec).size();
        long slide = spec instanceof WindowSpec.Tumbling tm2 ? tm2.size() : ((WindowSpec.Sliding) spec).slide();
        // brute force: scan candidate starts in a range around t
        for (long s = t - size - slide; s <= t; s++) {
            if (Math.floorMod(s, slide) == 0 && s <= t && t < s + size) {
                ws.add(new Window(s, s + size));
            }
        }
        return ws;
    }

    @Test
    void tumblingAndSlidingMatchBruteForceOracle() {
        for (int seed = 0; seed < 300; seed++) {
            Random rnd = new Random(seed);
            WindowSpec spec = seed % 2 == 0
                ? new WindowSpec.Tumbling(1 + rnd.nextInt(12))
                : sliding(rnd);
            long ooo = rnd.nextInt(8);
            long lateness = rnd.nextInt(15);
            List<Event> events = stream(rnd, 60, 3, 12);

            WindowEngine engine = new WindowEngine(spec, ooo, lateness);
            Map<String, Agg> lastEmitted = new HashMap<>();
            for (Event e : events) {
                for (Result r : engine.process(e)) {
                    lastEmitted.put(r.key() + "@" + r.window(), r.agg());
                }
                engine.checkInvariants();
            }
            for (Result r : engine.flush()) {
                lastEmitted.put(r.key() + "@" + r.window(), r.agg());
            }

            // Oracle: prefix watermark by naive O(n^2) loop, retained events per (key, window).
            Map<String, Agg> expected = new TreeMap<>();
            List<Dropped> expectedDrops = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                long prevWm = Long.MIN_VALUE;
                if (i > 0) {
                    long max = Long.MIN_VALUE;
                    for (int j = 0; j < i; j++) {
                        max = Math.max(max, events.get(j).time());
                    }
                    prevWm = max - ooo;
                }
                Event e = events.get(i);
                for (Window w : windows(spec, e.time())) {
                    if (w.end() + lateness <= prevWm) {
                        expectedDrops.add(new Dropped(e, w));
                    } else {
                        expected.merge(e.key() + "@" + w, Agg.of(e.value()), Agg::merge);
                    }
                }
            }
            assertEquals(expected, new TreeMap<>(lastEmitted), "seed " + seed + " spec " + spec);
            assertEquals(expectedDrops, engine.dropped(), "drops, seed " + seed + " spec " + spec);
        }
    }

    private static WindowSpec sliding(Random rnd) {
        int slide = 1 + rnd.nextInt(6);
        return new WindowSpec.Sliding(slide + rnd.nextInt(8), slide);
    }

    @Test
    void sessionsWithUnboundedLatenessMatchSortedMergeOracle() {
        for (int seed = 0; seed < 300; seed++) {
            Random rnd = new Random(1000 + seed);
            long gap = 1 + rnd.nextInt(8);
            List<Event> events = stream(rnd, 50, 3, 20);
            WindowEngine engine = new WindowEngine(new WindowSpec.Session(gap), rnd.nextInt(5), Long.MAX_VALUE);
            Map<String, Agg> lastEmitted = new HashMap<>();
            for (Event e : events) {
                for (Result r : engine.process(e)) {
                    lastEmitted.put(r.key() + "@" + r.window(), r.agg());
                }
                engine.checkInvariants();
            }
            for (Result r : engine.flush()) {
                lastEmitted.put(r.key() + "@" + r.window(), r.agg());
            }
            assertEquals(List.of(), engine.dropped(), "seed " + seed);

            Map<String, Agg> expected = new TreeMap<>();
            for (String key : List.of("k0", "k1", "k2")) {
                List<Event> mine = new ArrayList<>();
                for (Event e : events) {
                    if (e.key().equals(key)) {
                        mine.add(e);
                    }
                }
                mine.sort((a, b) -> Long.compare(a.time(), b.time()));
                long start = 0;
                long end = 0;
                Agg agg = null;
                for (Event e : mine) {
                    if (agg != null && e.time() <= end) {
                        end = Math.max(end, e.time() + gap);
                        agg = agg.merge(Agg.of(e.value()));
                    } else {
                        if (agg != null) {
                            expected.put(key + "@" + new Window(start, end), agg);
                        }
                        start = e.time();
                        end = e.time() + gap;
                        agg = Agg.of(e.value());
                    }
                }
                if (agg != null) {
                    expected.put(key + "@" + new Window(start, end), agg);
                }
            }
            for (Map.Entry<String, Agg> want : expected.entrySet()) {
                assertEquals(want.getValue(), lastEmitted.get(want.getKey()), "seed " + seed + " " + want.getKey());
            }
        }
    }

    @Test
    void checkpointAndRestoreAtRandomPointsEqualsUninterruptedRun() {
        for (int seed = 0; seed < 200; seed++) {
            Random rnd = new Random(5000 + seed);
            WindowSpec spec = switch (seed % 3) {
                case 0 -> new WindowSpec.Tumbling(1 + rnd.nextInt(10));
                case 1 -> sliding(rnd);
                default -> new WindowSpec.Session(1 + rnd.nextInt(8));
            };
            long ooo = rnd.nextInt(6);
            long lateness = rnd.nextInt(20);
            List<Event> events = stream(rnd, 50, 3, 10);

            WindowEngine straight = new WindowEngine(spec, ooo, lateness);
            List<Result> expected = new ArrayList<>();
            for (Event e : events) {
                expected.addAll(straight.process(e));
            }
            expected.addAll(straight.flush());

            WindowEngine crashing = new WindowEngine(spec, ooo, lateness);
            List<Result> actual = new ArrayList<>();
            for (Event e : events) {
                actual.addAll(crashing.process(e));
                if (rnd.nextInt(4) == 0) {
                    crashing = WindowEngine.restore(crashing.snapshot());
                    crashing.checkInvariants();
                }
            }
            actual.addAll(crashing.flush());

            assertEquals(expected, actual, "seed " + seed);
            assertEquals(straight.dropped(), crashing.dropped(), "seed " + seed);
            assertArrayEquals(straight.snapshot(), crashing.snapshot(), "seed " + seed);
        }
    }

    @Test
    void resultsOfEachCallAreSortedByEndStartKey() {
        Random rnd = new Random(7);
        WindowEngine engine = new WindowEngine(new WindowSpec.Sliding(8, 3), 2, 5);
        for (Event e : stream(rnd, 200, 4, 5)) {
            List<Result> out = engine.process(e);
            for (int i = 1; i < out.size(); i++) {
                Result a = out.get(i - 1);
                Result b = out.get(i);
                long[] x = {a.window().end(), a.window().start()};
                long[] y = {b.window().end(), b.window().start()};
                boolean ordered = x[0] < y[0] || (x[0] == y[0] && (x[1] < y[1]
                    || (x[1] == y[1] && a.key().compareTo(b.key()) <= 0)));
                assertTrue(ordered, a + " before " + b);
            }
        }
    }
}
