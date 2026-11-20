package ae.geom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Exact integer geometry predicates and algorithms (see TASK.md). */
public final class Geom {
    private Geom() {}

    /** Cross product of (b-a) and (c-a). With |coords| <= 1e9 the result is at most 8e18 in magnitude. */
    static long cross(Pt a, Pt b, Pt c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    /** +1 if a-&gt;b-&gt;c turns left, -1 if right, 0 if collinear. */
    public static int orient(Pt a, Pt b, Pt c) {
        return Long.signum(cross(a, b, c));
    }

    static long dist2(Pt a, Pt b) {
        long dx = a.x() - b.x();
        long dy = a.y() - b.y();
        return dx * dx + dy * dy;
    }

    /** True if p lies on the closed segment s (also for a zero-length segment). */
    public static boolean onSegment(Pt p, Seg s) {
        Pt a = s.a();
        Pt b = s.b();
        return orient(a, b, p) == 0
                && Math.min(a.x(), b.x()) <= p.x() && p.x() <= Math.max(a.x(), b.x())
                && Math.min(a.y(), b.y()) <= p.y() && p.y() <= Math.max(a.y(), b.y());
    }

    public static Rel segmentRelation(Seg s, Seg t) {
        Pt a = s.a();
        Pt b = s.b();
        Pt c = t.a();
        Pt d = t.b();
        boolean sPoint = a.equals(b);
        boolean tPoint = c.equals(d);
        if (sPoint && tPoint) {
            return a.equals(c) ? Rel.POINT : Rel.NONE;
        }
        if (sPoint) {
            return onSegment(a, t) ? Rel.POINT : Rel.NONE;
        }
        if (tPoint) {
            return onSegment(c, s) ? Rel.POINT : Rel.NONE;
        }
        int o1 = orient(a, b, c);
        int o2 = orient(a, b, d);
        int o3 = orient(c, d, a);
        int o4 = orient(c, d, b);
        if (o1 == 0 && o2 == 0) {
            // collinear (so o3 == o4 == 0 too): compare the lexicographic extents
            Pt lo1 = Pt.ORDER.compare(a, b) <= 0 ? a : b;
            Pt hi1 = lo1 == a ? b : a;
            Pt lo2 = Pt.ORDER.compare(c, d) <= 0 ? c : d;
            Pt hi2 = lo2 == c ? d : c;
            Pt lo = Pt.ORDER.compare(lo1, lo2) >= 0 ? lo1 : lo2;
            Pt hi = Pt.ORDER.compare(hi1, hi2) <= 0 ? hi1 : hi2;
            int cmp = Pt.ORDER.compare(lo, hi);
            return cmp > 0 ? Rel.NONE : cmp == 0 ? Rel.POINT : Rel.OVERLAP;
        }
        return o1 * o2 <= 0 && o3 * o4 <= 0 ? Rel.POINT : Rel.NONE;
    }

    public static boolean intersects(Seg s, Seg t) {
        return segmentRelation(s, t) != Rel.NONE;
    }

    /** Andrew's monotone chain. Counter-clockwise, no collinear vertices, starts at the smallest point. */
    public static List<Pt> convexHull(List<Pt> points) {
        Objects.requireNonNull(points, "points");
        Pt[] sorted = points.stream().sorted(Pt.ORDER).distinct().toArray(Pt[]::new);
        int n = sorted.length;
        if (n <= 2) {
            return new ArrayList<>(Arrays.asList(sorted));
        }
        Pt[] h = new Pt[2 * n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && orient(h[k - 2], h[k - 1], sorted[i]) <= 0) {
                k--;
            }
            h[k++] = sorted[i];
        }
        for (int i = n - 2, lower = k + 1; i >= 0; i--) {
            while (k >= lower && orient(h[k - 2], h[k - 1], sorted[i]) <= 0) {
                k--;
            }
            h[k++] = sorted[i];
        }
        return new ArrayList<>(Arrays.asList(h).subList(0, k - 1));
    }

    /** Twice the signed area (shoelace); overflow raises ArithmeticException. */
    public static long area2(List<Pt> polygon) {
        long sum = 0;
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            Pt p = polygon.get(i);
            Pt q = polygon.get((i + 1) % n);
            long term = Math.subtractExact(Math.multiplyExact(p.x(), q.y()), Math.multiplyExact(q.x(), p.y()));
            sum = Math.addExact(sum, term);
        }
        return sum;
    }

    /** Point location in a simple polygon (either orientation) using an exact crossing-number test. */
    public static Loc locate(List<Pt> polygon, Pt p) {
        int n = polygon.size();
        if (n < 3) {
            throw new IllegalArgumentException("polygon needs at least 3 vertices");
        }
        boolean inside = false;
        for (int i = 0; i < n; i++) {
            Pt a = polygon.get(i);
            Pt b = polygon.get((i + 1) % n);
            if (onSegment(p, new Seg(a, b))) {
                return Loc.BOUNDARY;
            }
            if ((a.y() > p.y()) != (b.y() > p.y())) {
                // edge straddles the horizontal line through p (half-open rule counts a vertex once)
                int side = orient(a, b, p);
                boolean up = b.y() > a.y();
                if (up ? side > 0 : side < 0) {
                    inside = !inside;
                }
            }
        }
        return inside ? Loc.INSIDE : Loc.OUTSIDE;
    }

    /** Strictly convex simple polygon in either orientation. */
    public static boolean isConvex(List<Pt> polygon) {
        int n = polygon.size();
        if (n < 3) {
            return false;
        }
        int sign = 0;
        long[] dx = new long[n];
        long[] dy = new long[n];
        for (int i = 0; i < n; i++) {
            Pt a = polygon.get(i);
            Pt b = polygon.get((i + 1) % n);
            Pt c = polygon.get((i + 2) % n);
            int o = orient(a, b, c);
            if (o == 0 || (sign != 0 && o != sign)) {
                return false;
            }
            sign = o;
            dx[i] = b.x() - a.x();
            dy[i] = b.y() - a.y();
        }
        // all turns equal: simple iff the direction does not wind more than once
        return signChanges(dx) <= 2 && signChanges(dy) <= 2;
    }

    private static int signChanges(long[] d) {
        int last = 0;
        int first = 0;
        int changes = 0;
        for (long v : d) {
            int s = Long.signum(v);
            if (s == 0) {
                continue;
            }
            if (first == 0) {
                first = s;
            } else if (s != last) {
                changes++;
            }
            last = s;
        }
        if (first != 0 && first != last) {
            changes++;
        }
        return changes;
    }

    /** Largest squared distance between two of the points (rotating calipers over the hull). */
    public static long diameter2(List<Pt> points) {
        List<Pt> h = convexHull(points);
        int n = h.size();
        if (n < 2) {
            return 0;
        }
        if (n == 2) {
            return dist2(h.get(0), h.get(1));
        }
        long best = 0;
        int j = 1;
        for (int i = 0; i < n; i++) {
            int ni = (i + 1) % n;
            while (cross(h.get(i), h.get(ni), h.get((j + 1) % n)) > cross(h.get(i), h.get(ni), h.get(j))) {
                j = (j + 1) % n;
            }
            best = Math.max(best, Math.max(dist2(h.get(i), h.get(j)), dist2(h.get(ni), h.get(j))));
        }
        return best;
    }

    /** Smallest squared distance between two list entries (divide and conquer). */
    public static long closestPair2(List<Pt> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("need at least 2 points");
        }
        Pt[] px = points.stream().sorted(Pt.ORDER).toArray(Pt[]::new);
        return closest(px, 0, px.length, new Pt[px.length]);
    }

    /** Works on px[lo,hi) sorted by x; leaves it sorted by y. */
    private static long closest(Pt[] px, int lo, int hi, Pt[] tmp) {
        int n = hi - lo;
        if (n <= 3) {
            long best = Long.MAX_VALUE;
            for (int i = lo; i < hi; i++) {
                for (int j = i + 1; j < hi; j++) {
                    best = Math.min(best, dist2(px[i], px[j]));
                }
            }
            Arrays.sort(px, lo, hi, (u, v) -> Long.compare(u.y(), v.y()));
            return best;
        }
        int mid = lo + n / 2;
        long midX = px[mid].x();
        long d = Math.min(closest(px, lo, mid, tmp), closest(px, mid, hi, tmp));
        // merge by y
        int a = lo;
        int b = mid;
        int k = lo;
        while (a < mid && b < hi) {
            tmp[k++] = px[a].y() <= px[b].y() ? px[a++] : px[b++];
        }
        while (a < mid) {
            tmp[k++] = px[a++];
        }
        while (b < hi) {
            tmp[k++] = px[b++];
        }
        System.arraycopy(tmp, lo, px, lo, n);
        if (d == 0) {
            return 0;
        }
        int m = 0;
        for (int i = lo; i < hi; i++) {
            long dx = px[i].x() - midX;
            if (dx * dx < d) {
                for (int j = m - 1; j >= 0; j--) {
                    long dy = px[i].y() - tmp[j].y();
                    if (dy * dy >= d) {
                        break;
                    }
                    d = Math.min(d, dist2(px[i], tmp[j]));
                }
                tmp[m++] = px[i];
            }
        }
        return d;
    }
}
