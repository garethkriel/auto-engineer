package ae.geom;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class GeomTest {
    static Pt p(long x, long y) { return new Pt(x, y); }
    static List<Pt> pts(long... xy) {
        List<Pt> l = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) l.add(p(xy[i], xy[i + 1]));
        return l;
    }

    @Test void orientSigns() {
        assertEquals(1, Geom.orient(p(0, 0), p(1, 0), p(1, 1)));
        assertEquals(-1, Geom.orient(p(0, 0), p(1, 0), p(1, -1)));
        assertEquals(0, Geom.orient(p(0, 0), p(2, 2), p(5, 5)));
    }

    @Test void orientExtremeCoordinatesDoNotOverflow() {
        long m = 1_000_000_000L;
        assertEquals(1, Geom.orient(p(-m, -m), p(m, -m), p(m, m)));
        assertEquals(-1, Geom.orient(p(-m, -m), p(m, m), p(m, -m)));
        assertEquals(0, Geom.orient(p(-m, -m), p(0, 0), p(m, m)));
    }

    @Test void coordinateRangeEnforced() {
        assertThrows(IllegalArgumentException.class, () -> p(1_000_000_001L, 0));
        assertThrows(IllegalArgumentException.class, () -> p(0, -1_000_000_001L));
        assertDoesNotThrow(() -> p(-1_000_000_000L, 1_000_000_000L));
    }

    @Test void segmentRelationLiteralCases() {
        assertEquals(Rel.POINT, Geom.segmentRelation(new Seg(p(0, 0), p(4, 4)), new Seg(p(0, 4), p(4, 0))));
        assertEquals(Rel.POINT, Geom.segmentRelation(new Seg(p(0, 0), p(2, 0)), new Seg(p(2, 0), p(2, 5)))); // endpoint touch
        assertEquals(Rel.POINT, Geom.segmentRelation(new Seg(p(0, 0), p(2, 0)), new Seg(p(2, 0), p(5, 0)))); // collinear touch
        assertEquals(Rel.OVERLAP, Geom.segmentRelation(new Seg(p(0, 0), p(3, 0)), new Seg(p(2, 0), p(5, 0))));
        assertEquals(Rel.NONE, Geom.segmentRelation(new Seg(p(0, 0), p(1, 0)), new Seg(p(2, 0), p(5, 0))));
        assertEquals(Rel.NONE, Geom.segmentRelation(new Seg(p(0, 0), p(4, 0)), new Seg(p(0, 1), p(4, 1))));
        assertEquals(Rel.NONE, Geom.segmentRelation(new Seg(p(0, 0), p(2, 2)), new Seg(p(3, 0), p(3, 9))));
        // degenerate point segments
        assertEquals(Rel.POINT, Geom.segmentRelation(new Seg(p(1, 1), p(1, 1)), new Seg(p(0, 0), p(2, 2))));
        assertEquals(Rel.NONE, Geom.segmentRelation(new Seg(p(1, 2), p(1, 2)), new Seg(p(0, 0), p(2, 2))));
        assertEquals(Rel.POINT, Geom.segmentRelation(new Seg(p(1, 1), p(1, 1)), new Seg(p(1, 1), p(1, 1))));
        assertEquals(Rel.NONE, Geom.segmentRelation(new Seg(p(1, 1), p(1, 1)), new Seg(p(2, 2), p(2, 2))));
    }

    @Test void segmentRelationNearMissWithBigCoordinates() {
        long m = 1_000_000_000L;
        // line from (-m,-m) to (m, m-1) passes just below (m, m): miss
        assertEquals(Rel.NONE, Geom.segmentRelation(new Seg(p(-m, -m), p(m, m - 1)), new Seg(p(m, m), p(0, m))));
        assertEquals(Rel.POINT, Geom.segmentRelation(new Seg(p(-m, -m), p(m, m)), new Seg(p(m, m), p(0, m))));
    }

    @Test void hullLiteral() {
        // square with interior and collinear boundary points and duplicates
        List<Pt> in = pts(0, 0, 4, 0, 4, 4, 0, 4, 2, 2, 2, 0, 4, 2, 0, 0, 1, 3);
        assertEquals(pts(0, 0, 4, 0, 4, 4, 0, 4), Geom.convexHull(in));
    }

    @Test void hullSmallInputs() {
        assertEquals(List.of(), Geom.convexHull(List.of()));
        assertEquals(pts(3, 3), Geom.convexHull(pts(3, 3, 3, 3)));
        assertEquals(pts(0, 0, 5, 5), Geom.convexHull(pts(5, 5, 2, 2, 0, 0, 3, 3)));
        assertEquals(pts(1, 0, 1, 9), Geom.convexHull(pts(1, 9, 1, 4, 1, 0)));
        assertEquals(pts(0, 0, 2, 0, 1, 1), Geom.convexHull(pts(2, 0, 1, 1, 0, 0)));
    }

    @Test void hullDoesNotMutateInput() {
        List<Pt> in = new ArrayList<>(pts(3, 1, 0, 0, 2, 5));
        List<Pt> copy = new ArrayList<>(in);
        Geom.convexHull(in);
        assertEquals(copy, in);
    }

    @Test void hullRandomMatchesBruteForceExtremePoints() {
        Random r = new Random(7);
        for (int iter = 0; iter < 400; iter++) {
            int n = 1 + r.nextInt(14);
            int range = 1 + r.nextInt(6);
            List<Pt> in = new ArrayList<>();
            for (int i = 0; i < n; i++) in.add(p(r.nextInt(range * 2 + 1) - range, r.nextInt(range * 2 + 1) - range));
            List<Pt> hull = Geom.convexHull(in);
            Set<Pt> expected = new TreeSet<>(Pt.ORDER);
            expected.addAll(Oracle.extremePoints(in));
            Set<Pt> actual = new TreeSet<>(Pt.ORDER);
            actual.addAll(hull);
            assertEquals(expected, actual, "points " + in);
            if (hull.size() >= 3) {
                assertTrue(Geom.area2(hull) > 0, "ccw " + in);
                assertTrue(Geom.isConvex(hull), "convex " + in);
                for (Pt q : in) assertNotEquals(Loc.OUTSIDE, Geom.locate(hull, q), "contains " + q + " in " + in);
            }
            assertEquals(Collections.min(in, Pt.ORDER), hull.get(0), "starts at lexicographic min of " + in);
        }
    }

    @Test void area2Literals() {
        assertEquals(32, Geom.area2(pts(0, 0, 4, 0, 4, 4, 0, 4)));
        assertEquals(-32, Geom.area2(pts(0, 0, 0, 4, 4, 4, 4, 0)));
        assertEquals(0, Geom.area2(pts(0, 0, 1, 1, 2, 2)));
        assertEquals(0, Geom.area2(pts(1, 1)));
    }

    @Test void area2OverflowDetected() {
        long m = 1_000_000_000L;
        assertEquals(8_000_000_000_000_000_000L, Geom.area2(pts(-m, -m, m, -m, m, m, -m, m)));
        // traversing the square twice gives 1.6e19 > Long.MAX_VALUE
        List<Pt> twice = new ArrayList<>();
        for (int i = 0; i < 2; i++) twice.addAll(pts(-m, -m, m, -m, m, m, -m, m));
        assertThrows(ArithmeticException.class, () -> Geom.area2(twice));
    }

    @Test void locateLiteralConcavePolygon() {
        // "U" shape (CCW): notch between x=3..6 above y=4
        List<Pt> u = pts(0, 0, 9, 0, 9, 9, 6, 9, 6, 4, 3, 4, 3, 9, 0, 9);
        assertEquals(Loc.INSIDE, Geom.locate(u, p(1, 5)));
        assertEquals(Loc.INSIDE, Geom.locate(u, p(4, 2)));
        assertEquals(Loc.OUTSIDE, Geom.locate(u, p(4, 6)));   // in the notch
        assertEquals(Loc.BOUNDARY, Geom.locate(u, p(4, 4)));  // notch floor
        assertEquals(Loc.BOUNDARY, Geom.locate(u, p(6, 6)));  // notch wall
        assertEquals(Loc.BOUNDARY, Geom.locate(u, p(0, 0)));  // vertex
        assertEquals(Loc.OUTSIDE, Geom.locate(u, p(-1, 4)));
        assertEquals(Loc.OUTSIDE, Geom.locate(u, p(10, 4)));
        assertEquals(Loc.OUTSIDE, Geom.locate(u, p(4, 14)));
    }

    @Test void locateRayThroughVertexIsCountedOnce() {
        // diamond: a horizontal ray at y=2 passes exactly through vertices (0,2) and (4,2)
        List<Pt> d = pts(2, 0, 4, 2, 2, 4, 0, 2);
        assertEquals(Loc.INSIDE, Geom.locate(d, p(1, 2)));
        assertEquals(Loc.INSIDE, Geom.locate(d, p(2, 2)));
        assertEquals(Loc.OUTSIDE, Geom.locate(d, p(-1, 2)));
        assertEquals(Loc.OUTSIDE, Geom.locate(d, p(5, 2)));
        assertEquals(Loc.OUTSIDE, Geom.locate(d, p(2, 5)));
        assertEquals(Loc.BOUNDARY, Geom.locate(d, p(3, 3)));
    }

    @Test void locateOrientationIndependent() {
        List<Pt> u = pts(0, 0, 9, 0, 9, 9, 6, 9, 6, 4, 3, 4, 3, 9, 0, 9);
        List<Pt> rev = new ArrayList<>(u);
        Collections.reverse(rev);
        for (int x = -2; x <= 11; x++)
            for (int y = -2; y <= 11; y++)
                assertEquals(Geom.locate(u, p(x, y)), Geom.locate(rev, p(x, y)), x + "," + y);
    }

    @Test void locateTooFewVertices() {
        assertThrows(IllegalArgumentException.class, () -> Geom.locate(pts(0, 0, 1, 1), p(0, 0)));
    }

    @Test void locateRandomConvexAgainstEdgeTest() {
        Random r = new Random(11);
        int checked = 0;
        for (int iter = 0; iter < 200; iter++) {
            List<Pt> in = new ArrayList<>();
            for (int i = 0; i < 12; i++) in.add(p(r.nextInt(13) - 6, r.nextInt(13) - 6));
            List<Pt> hull = Geom.convexHull(in);
            if (hull.size() < 3) continue;
            checked++;
            for (int x = -8; x <= 8; x++)
                for (int y = -8; y <= 8; y++)
                    assertEquals(Oracle.locateConvex(hull, p(x, y)), Geom.locate(hull, p(x, y)),
                            "hull " + hull + " q " + x + "," + y);
        }
        assertTrue(checked > 150, "checked " + checked);
    }

    @Test void isConvexLiterals() {
        assertTrue(Geom.isConvex(pts(0, 0, 4, 0, 4, 4, 0, 4)));
        assertTrue(Geom.isConvex(pts(0, 0, 0, 4, 4, 4, 4, 0)));
        assertFalse(Geom.isConvex(pts(0, 0, 4, 0, 4, 4, 2, 1, 0, 4)));      // reflex vertex
        assertFalse(Geom.isConvex(pts(0, 0, 2, 0, 4, 0, 4, 4, 0, 4)));      // collinear triple
        assertFalse(Geom.isConvex(pts(0, 0, 4, 0, 0, 4, 4, 4)));            // bow-tie: turns alternate
        // pentagram: every turn is a left turn but the boundary winds twice
        assertFalse(Geom.isConvex(pts(0, 0, 6, 3, -1, 3, 4, 0, 3, 6)));
        assertTrue(Geom.isConvex(pts(0, 0, 4, 0, 6, 3, 3, 6, -1, 3)));      // the pentagon it was built from
        assertFalse(Geom.isConvex(pts(0, 0, 1, 1)));
    }

    @Test void diameterLiteralsAndRandom() {
        assertEquals(0, Geom.diameter2(List.of()));
        assertEquals(0, Geom.diameter2(pts(1, 1)));
        assertEquals(25, Geom.diameter2(pts(0, 0, 3, 4)));
        assertEquals(32, Geom.diameter2(pts(0, 0, 4, 0, 4, 4, 0, 4, 2, 2)));
        long m = 1_000_000_000L;
        assertEquals(8_000_000_000_000_000_000L, Geom.diameter2(pts(-m, -m, m, m, 0, 0)));
        Random r = new Random(3);
        for (int iter = 0; iter < 500; iter++) {
            int n = 1 + r.nextInt(15);
            int range = 1 + r.nextInt(30);
            List<Pt> in = new ArrayList<>();
            for (int i = 0; i < n; i++) in.add(p(r.nextInt(2 * range + 1) - range, r.nextInt(2 * range + 1) - range));
            assertEquals(Oracle.diameter2(in), Geom.diameter2(in), "points " + in);
        }
    }

    @Test void closestPairLiteralsAndRandom() {
        assertThrows(IllegalArgumentException.class, () -> Geom.closestPair2(pts(1, 1)));
        assertEquals(2, Geom.closestPair2(pts(0, 0, 10, 10, 11, 11, 50, 0)));
        assertEquals(0, Geom.closestPair2(pts(5, 5, 9, 9, 5, 5)));
        assertEquals(1, Geom.closestPair2(pts(0, 0, 1, 0)));
        // points on a vertical line (gaps 10) plus one at y=235, 5 from y=230 and 5 from y=240: strip handling
        List<Pt> line = new ArrayList<>();
        for (int i = 0; i < 50; i++) line.add(p(7, i * 10));
        line.add(p(7, 235));
        assertEquals(25, Geom.closestPair2(line));
        Random r = new Random(5);
        for (int iter = 0; iter < 500; iter++) {
            int n = 2 + r.nextInt(40);
            int range = 1 + r.nextInt(200);
            List<Pt> in = new ArrayList<>();
            for (int i = 0; i < n; i++) in.add(p(r.nextInt(2 * range + 1) - range, r.nextInt(2 * range + 1) - range));
            assertEquals(Oracle.closest2(in), Geom.closestPair2(in), "points " + in);
        }
    }

    @Test void closestPairDoesNotMutateAndHandlesHugeCoordinates() {
        long m = 1_000_000_000L;
        List<Pt> in = new ArrayList<>(pts(-m, -m, m, m, -m, m, m, -m));
        List<Pt> copy = new ArrayList<>(in);
        assertEquals(4_000_000_000_000_000_000L, Geom.closestPair2(in));
        assertEquals(copy, in);
    }
}
