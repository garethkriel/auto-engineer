package ae.spatial;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Randomized comparison of {@link SpatialIndex} against a brute-force model. */
class DifferentialTest {
    private static final class Model {
        final Map<String, Point> pts = new TreeMap<>();

        List<Entry> all() {
            List<Entry> out = new ArrayList<>();
            pts.forEach((id, p) -> out.add(new Entry(id, p)));
            return out;
        }

        List<Entry> range(Rect r) {
            return all().stream().filter(e -> r.contains(e.point())).toList();
        }

        private static double d2(Point p, double x, double y) {
            return (p.x() - x) * (p.x() - x) + (p.y() - y) * (p.y() - y);
        }

        List<Entry> byDistance(double x, double y) {
            List<Entry> out = new ArrayList<>(all());
            out.sort(Comparator.comparingDouble((Entry e) -> d2(e.point(), x, y)).thenComparing(Entry::id));
            return out;
        }

        List<Entry> radius(double x, double y, double r) {
            return byDistance(x, y).stream().filter(e -> d2(e.point(), x, y) <= r * r).toList();
        }

        List<Entry> nearest(double x, double y, int k) {
            List<Entry> all = byDistance(x, y);
            return all.subList(0, Math.min(k, all.size()));
        }
    }

    /** Coordinates on a coarse lattice so ties and midline hits are frequent. */
    private static double coord(Random rnd, int span) {
        return rnd.nextInt(span) * 0.5 - span / 4.0;
    }

    private static void sameEntries(List<Entry> expected, List<Entry> actual, String what) {
        assertEquals(expected, actual, what);
    }

    private void run(long seed, int steps, int span) {
        Random rnd = new Random(seed);
        SpatialIndex ix = new SpatialIndex();
        Model m = new Model();
        int next = 0;
        for (int step = 0; step < steps; step++) {
            int op = rnd.nextInt(10);
            String where = "seed " + seed + " step " + step;
            if (op < 4 || m.pts.isEmpty()) {
                String id = "n" + (next++);
                double x = coord(rnd, span);
                double y = coord(rnd, span);
                ix.insert(id, x, y);
                m.pts.put(id, new Point(x, y));
            } else if (op < 6) {
                String id = pick(m, rnd);
                assertTrue(ix.remove(id), where);
                m.pts.remove(id);
            } else if (op < 8) {
                String id = pick(m, rnd);
                double x = coord(rnd, span);
                double y = coord(rnd, span);
                ix.move(id, x, y);
                m.pts.put(id, new Point(x, y));
            } else {
                double x = coord(rnd, span);
                double y = coord(rnd, span);
                double w = rnd.nextInt(span) * 0.5;
                double h = rnd.nextInt(span) * 0.5;
                Rect r = new Rect(x, y, x + w, y + h);
                sameEntries(m.range(r), ix.range(r), where + " range " + r);
                double rad = rnd.nextInt(span) * 0.5;
                sameEntries(m.radius(x, y, rad), ix.radius(x, y, rad), where + " radius");
                int k = rnd.nextInt(12);
                sameEntries(m.nearest(x, y, k), ix.nearest(x, y, k), where + " nearest k=" + k);
            }
            ix.checkInvariants();
            assertEquals(m.pts.size(), ix.size(), where);
        }
        sameEntries(m.all(), ix.range(new Rect(-1e9, -1e9, 1e9, 1e9)), "final contents seed " + seed);
    }

    private static String pick(Model m, Random rnd) {
        List<String> keys = new ArrayList<>(m.pts.keySet());
        return keys.get(rnd.nextInt(keys.size()));
    }

    @Test
    void denseLatticeWithManyTies() {
        for (long seed = 1; seed <= 6; seed++) {
            run(seed, 700, 12);
        }
    }

    @Test
    void sparseWideSpread() {
        for (long seed = 100; seed <= 103; seed++) {
            run(seed, 700, 4000);
        }
    }

    @Test
    void polygonMatchesBruteForceCrossingNumber() {
        Random rnd = new Random(5);
        for (int round = 0; round < 40; round++) {
            SpatialIndex ix = new SpatialIndex();
            Model m = new Model();
            for (int i = 0; i < 120; i++) {
                double x = rnd.nextInt(21) - 10;
                double y = rnd.nextInt(21) - 10;
                ix.insert("q" + i, x, y);
                m.pts.put("q" + i, new Point(x, y));
            }
            // convex-ish star polygon built by sorting integer vertices by angle around the origin
            int n = 3 + rnd.nextInt(6);
            List<Point> verts = new ArrayList<>();
            double[] angles = new double[n];
            for (int i = 0; i < n; i++) {
                angles[i] = (i + rnd.nextDouble() * 0.8) * 2 * Math.PI / n;
            }
            for (int i = 0; i < n; i++) {
                double rad = 3 + rnd.nextInt(8);
                verts.add(new Point(Math.round(rad * Math.cos(angles[i])), Math.round(rad * Math.sin(angles[i]))));
            }
            List<Entry> expected = new ArrayList<>();
            for (Entry e : m.all()) {
                if (Oracle.inPolygon(verts, e.point())) {
                    expected.add(e);
                }
            }
            List<String> got = ix.inPolygon(verts).stream().map(Entry::id).toList();
            assertEquals(expected.stream().map(Entry::id).toList(), got, "round " + round + " " + verts);
        }
    }

    /** Independent polygon oracle: boundary test first, then a cross-product crossing number. */
    static final class Oracle {
        static boolean inPolygon(List<Point> v, Point p) {
            int n = v.size();
            for (int i = 0; i < n; i++) {
                Point a = v.get(i);
                Point b = v.get((i + 1) % n);
                double cross = (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
                boolean within = p.x() >= Math.min(a.x(), b.x()) && p.x() <= Math.max(a.x(), b.x())
                        && p.y() >= Math.min(a.y(), b.y()) && p.y() <= Math.max(a.y(), b.y());
                if (cross == 0 && within) {
                    return true;
                }
            }
            // Crossing number decided by the sign of the cross product (exact on integer lattices),
            // not by intersecting at a shifted height, which moves the hit point on slanted edges.
            boolean inside = false;
            for (int i = 0; i < n; i++) {
                Point a = v.get(i);
                Point b = v.get((i + 1) % n);
                double cross = (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
                boolean upward = a.y() <= p.y() && b.y() > p.y();
                boolean downward = b.y() <= p.y() && a.y() > p.y();
                if ((upward && cross > 0) || (downward && cross < 0)) {
                    inside = !inside;
                }
            }
            return inside;
        }
    }

    @Test
    void largeIndexStaysShallowAndFast() {
        SpatialIndex ix = new SpatialIndex();
        Random rnd = new Random(1);
        long start = System.nanoTime();
        for (int i = 0; i < 20000; i++) {
            ix.insert("L" + i, rnd.nextDouble() * 1000, rnd.nextDouble() * 1000);
        }
        ix.checkInvariants();
        assertTrue(ix.depth() <= 12, "depth " + ix.depth());
        for (int i = 0; i < 2000; i++) {
            assertEquals(5, ix.nearest(rnd.nextDouble() * 1000, rnd.nextDouble() * 1000, 5).size());
        }
        assertTrue(System.nanoTime() - start < 10_000_000_000L, "too slow");
    }
}
