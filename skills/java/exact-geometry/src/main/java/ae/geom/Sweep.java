package ae.geom;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/** Shamos-Hoey sweep: finds any intersecting pair of segments in O(n log n) with exact arithmetic. */
public final class Sweep {
    private Sweep() {}

    private record Event(long x, int type, int id) {} // type 0 = insert (left end), 1 = remove (right end)

    public static Optional<int[]> findIntersection(List<Seg> input) {
        int n = input.size();
        List<Seg> segs = new ArrayList<>(n); // left end first
        for (Seg s : input) {
            segs.add(Pt.ORDER.compare(s.a(), s.b()) <= 0 ? s : new Seg(s.b(), s.a()));
        }
        List<Event> events = new ArrayList<>(2 * n);
        for (int i = 0; i < n; i++) {
            events.add(new Event(segs.get(i).a().x(), 0, i));
            events.add(new Event(segs.get(i).b().x(), 1, i));
        }
        events.sort(Comparator.comparingLong(Event::x).thenComparingInt(Event::type).thenComparingInt(Event::id));

        TreeSet<Integer> active = new TreeSet<>((i, j) -> {
            if (i.equals(j)) {
                return 0;
            }
            int c = compareAtCommonX(segs.get(i), segs.get(j));
            return c != 0 ? c : Integer.compare(i, j);
        });
        for (Event e : events) {
            Integer id = e.id();
            if (e.type() == 0) {
                Integer next = active.higher(id);
                Integer prev = active.lower(id);
                if (next != null && Geom.intersects(segs.get(next), segs.get(id))) {
                    return Optional.of(pair(next, id));
                }
                if (prev != null && Geom.intersects(segs.get(prev), segs.get(id))) {
                    return Optional.of(pair(prev, id));
                }
                active.add(id);
            } else {
                Integer next = active.higher(id);
                Integer prev = active.lower(id);
                if (next != null && prev != null && Geom.intersects(segs.get(next), segs.get(prev))) {
                    return Optional.of(pair(next, prev));
                }
                active.remove(id);
            }
        }
        return Optional.empty();
    }

    private static int[] pair(int i, int j) {
        return new int[] {Math.min(i, j), Math.max(i, j)};
    }

    /** Compares the y values of two segments at x = max(left x); a vertical segment counts as its lower end. */
    private static int compareAtCommonX(Seg s, Seg t) {
        long x = Math.max(s.a().x(), t.a().x());
        BigInteger[] ys = yAt(s, x);
        BigInteger[] yt = yAt(t, x);
        // ys[0]/ys[1] vs yt[0]/yt[1], denominators positive
        return ys[0].multiply(yt[1]).compareTo(yt[0].multiply(ys[1]));
    }

    /** y at x as {numerator, denominator>0}. */
    private static BigInteger[] yAt(Seg s, long x) {
        Pt p = s.a();
        Pt q = s.b();
        if (p.x() == q.x()) {
            return new BigInteger[] {BigInteger.valueOf(p.y()), BigInteger.ONE};
        }
        long dx = q.x() - p.x();
        BigInteger num = BigInteger.valueOf(p.y()).multiply(BigInteger.valueOf(dx))
                .add(BigInteger.valueOf(q.y() - p.y()).multiply(BigInteger.valueOf(x - p.x())));
        return new BigInteger[] {num, BigInteger.valueOf(dx)};
    }
}
