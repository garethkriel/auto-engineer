package ae.geom;

import java.util.*;

/** Brute-force oracles written independently of the production code (plain long math, all-triples). */
final class Oracle {
    private Oracle() {}

    static long cross(Pt a, Pt b, Pt c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    /** Distinct points that are NOT in the closed convex span of other distinct points (segments and triangles). */
    static List<Pt> extremePoints(List<Pt> in) {
        List<Pt> d = new ArrayList<>(new TreeSet<>(in.stream().map(Oracle::key).toList()).stream().map(Oracle::unkey).toList());
        List<Pt> out = new ArrayList<>();
        for (Pt c : d) {
            boolean interior = false;
            for (Pt a : d) for (Pt b : d) {
                if (a.equals(c) || b.equals(c) || a.equals(b)) continue;
                if (cross(a, b, c) == 0 && Math.min(a.x(), b.x()) <= c.x() && c.x() <= Math.max(a.x(), b.x())
                        && Math.min(a.y(), b.y()) <= c.y() && c.y() <= Math.max(a.y(), b.y())) interior = true;
            }
            for (int i = 0; i < d.size() && !interior; i++) for (int j = i + 1; j < d.size() && !interior; j++)
                for (int k = j + 1; k < d.size() && !interior; k++) {
                    Pt a = d.get(i), b = d.get(j), e = d.get(k);
                    if (a.equals(c) || b.equals(c) || e.equals(c) || cross(a, b, e) == 0) continue;
                    long c1 = cross(a, b, c), c2 = cross(b, e, c), c3 = cross(e, a, c);
                    if ((c1 >= 0 && c2 >= 0 && c3 >= 0) || (c1 <= 0 && c2 <= 0 && c3 <= 0)) interior = true;
                }
            if (!interior) out.add(c);
        }
        return out;
    }

    private static String key(Pt p) { return p.x() + "," + p.y(); }
    private static Pt unkey(String s) { String[] t = s.split(","); return new Pt(Long.parseLong(t[0]), Long.parseLong(t[1])); }

    static Loc locateConvex(List<Pt> ccwHull, Pt q) {
        boolean onEdge = false;
        for (int i = 0; i < ccwHull.size(); i++) {
            long c = cross(ccwHull.get(i), ccwHull.get((i + 1) % ccwHull.size()), q);
            if (c < 0) return Loc.OUTSIDE;
            if (c == 0) onEdge = true;
        }
        return onEdge ? Loc.BOUNDARY : Loc.INSIDE;
    }

    static long d2(Pt a, Pt b) {
        long dx = a.x() - b.x(), dy = a.y() - b.y();
        return dx * dx + dy * dy;
    }

    static long diameter2(List<Pt> in) {
        long best = 0;
        for (Pt a : in) for (Pt b : in) best = Math.max(best, d2(a, b));
        return best;
    }

    static long closest2(List<Pt> in) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < in.size(); i++) for (int j = i + 1; j < in.size(); j++) best = Math.min(best, d2(in.get(i), in.get(j)));
        return best;
    }

    /** Independent test: bounding boxes overlap and each segment's endpoints do not lie strictly on one side of the other. */
    static boolean segInter(Seg s, Seg t) {
        Pt a = s.a(), b = s.b(), c = t.a(), d = t.b();
        if (Math.max(a.x(), b.x()) < Math.min(c.x(), d.x()) || Math.max(c.x(), d.x()) < Math.min(a.x(), b.x())) return false;
        if (Math.max(a.y(), b.y()) < Math.min(c.y(), d.y()) || Math.max(c.y(), d.y()) < Math.min(a.y(), b.y())) return false;
        return Long.signum(cross(a, b, c)) * Long.signum(cross(a, b, d)) <= 0
                && Long.signum(cross(c, d, a)) * Long.signum(cross(c, d, b)) <= 0;
    }

    static boolean anyIntersection(List<Seg> segs) {
        for (int i = 0; i < segs.size(); i++) for (int j = i + 1; j < segs.size(); j++)
            if (segInter(segs.get(i), segs.get(j))) return true;
        return false;
    }
}
