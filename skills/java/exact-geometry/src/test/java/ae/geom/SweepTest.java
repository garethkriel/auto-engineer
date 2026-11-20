package ae.geom;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SweepTest {
    static Seg s(long x1, long y1, long x2, long y2) { return new Seg(new Pt(x1, y1), new Pt(x2, y2)); }

    private static void assertValidPair(List<Seg> segs, Optional<int[]> got) {
        assertTrue(got.isPresent(), "expected an intersection in " + segs);
        int i = got.get()[0], j = got.get()[1];
        assertTrue(0 <= i && i < j && j < segs.size(), "indexes " + i + "," + j);
        assertTrue(Geom.intersects(segs.get(i), segs.get(j)), "reported pair must intersect: " + segs.get(i) + " " + segs.get(j));
    }

    @Test void emptyAndSingle() {
        assertTrue(Sweep.findIntersection(List.of()).isEmpty());
        assertTrue(Sweep.findIntersection(List.of(s(0, 0, 5, 5))).isEmpty());
    }

    @Test void simpleCrossing() {
        assertArrayEquals(new int[] {0, 1}, Sweep.findIntersection(List.of(s(0, 0, 4, 4), s(0, 4, 4, 0))).orElseThrow());
    }

    @Test void parallelStackNoIntersection() {
        List<Seg> l = new ArrayList<>();
        for (int i = 0; i < 20; i++) l.add(s(0, i, 10, i + 3));
        assertTrue(Sweep.findIntersection(l).isEmpty());
    }

    @Test void touchingEndpointCounts() {
        List<Seg> l = List.of(s(0, 0, 2, 2), s(2, 2, 5, 0));
        assertValidPair(l, Sweep.findIntersection(l));
    }

    @Test void tJunctionAndVerticalSegments() {
        List<Seg> t = List.of(s(0, 0, 10, 0), s(5, 0, 5, 9));
        assertValidPair(t, Sweep.findIntersection(t));
        List<Seg> v = List.of(s(3, 0, 3, 10), s(0, 5, 3, 5));
        assertValidPair(v, Sweep.findIntersection(v));
        List<Seg> miss = List.of(s(3, 0, 3, 10), s(0, 5, 2, 5), s(4, 0, 4, 10));
        assertTrue(Sweep.findIntersection(miss).isEmpty());
    }

    @Test void collinearOverlapAndPointSegments() {
        List<Seg> o = List.of(s(0, 0, 5, 0), s(3, 0, 8, 0));
        assertValidPair(o, Sweep.findIntersection(o));
        List<Seg> pt = List.of(s(2, 2, 2, 2), s(0, 0, 4, 4));
        assertValidPair(pt, Sweep.findIntersection(pt));
        List<Seg> dup = List.of(s(1, 1, 1, 1), s(1, 1, 1, 1));
        assertValidPair(dup, Sweep.findIntersection(dup));
        List<Seg> offPt = List.of(s(2, 3, 2, 3), s(0, 0, 4, 4));
        assertTrue(Sweep.findIntersection(offPt).isEmpty());
    }

    @Test void crossingHiddenBetweenNonCrossingSegmentsIsFound() {
        List<Seg> l = List.of(s(0, 0, 100, 0), s(0, 10, 100, 10), s(0, 20, 100, 20), s(50, 30, 50, -5));
        assertValidPair(l, Sweep.findIntersection(l));
    }

    @Test void reversedEndpointsAreNormalised() {
        List<Seg> l = List.of(s(4, 4, 0, 0), s(4, 0, 0, 4));
        assertValidPair(l, Sweep.findIntersection(l));
    }

    @Test void nearMissWithHugeCoordinatesNeedsExactArithmetic() {
        long m = 1_000_000_000L;
        List<Seg> miss = List.of(s(-m, -m, m, m - 1), s(-m, m, m, m)); // ends 1 apart at x=m, never closer
        assertTrue(Sweep.findIntersection(miss).isEmpty());
        List<Seg> hit = List.of(s(-m, -m, m, m), s(-m, m, m, -m));
        assertValidPair(hit, Sweep.findIntersection(hit));
        List<Seg> slender = List.of(s(-m, -m, m, m - 1), s(-m, m - 1, m, -m));
        assertValidPair(slender, Sweep.findIntersection(slender));
    }

    @Test void inputNotMutated() {
        List<Seg> l = new ArrayList<>(List.of(s(4, 4, 0, 0), s(0, 9, 3, 9)));
        List<Seg> copy = new ArrayList<>(l);
        Sweep.findIntersection(l);
        assertEquals(copy, l);
    }

    @Test void randomDifferentialSparseAndDense() {
        Random r = new Random(2026);
        int found = 0, none = 0;
        for (int iter = 0; iter < 3000; iter++) {
            int n = 2 + r.nextInt(9);
            int range = 2 + r.nextInt(r.nextBoolean() ? 4 : 25);
            List<Seg> segs = new ArrayList<>();
            for (int i = 0; i < n; i++) segs.add(s(r.nextInt(range), r.nextInt(range), r.nextInt(range), r.nextInt(range)));
            boolean expect = Oracle.anyIntersection(segs);
            Optional<int[]> got = Sweep.findIntersection(segs);
            assertEquals(expect, got.isPresent(), "segments " + segs);
            if (got.isPresent()) { assertValidPair(segs, got); found++; } else none++;
        }
        assertTrue(found > 300 && none > 100, "outcome mix found=" + found + " none=" + none);
    }

    /** Sparse generator: segments confined to disjoint y-bands never intersect (checked first), then a spanning one is added. */
    @Test void bandsAreDisjointThenSpanningSegmentAdded() {
        Random r = new Random(99);
        int hits = 0, misses = 0;
        for (int iter = 0; iter < 300; iter++) {
            int n = 3 + r.nextInt(12);
            List<Seg> segs = new ArrayList<>();
            for (int i = 0; i < n; i++)
                segs.add(s(r.nextInt(50), 10L * i + r.nextInt(4), r.nextInt(50), 10L * i + r.nextInt(4))); // band i: y in [10i, 10i+3]
            assertTrue(Sweep.findIntersection(segs).isEmpty(), "bands " + segs);
            int k = r.nextInt(n);
            segs.add(s(r.nextInt(50), 10L * k - 1, r.nextInt(50), 10L * k + 4));
            boolean expect = Oracle.anyIntersection(segs);
            Optional<int[]> got = Sweep.findIntersection(segs);
            assertEquals(expect, got.isPresent(), "planted " + segs);
            if (expect) { assertValidPair(segs, got); hits++; } else misses++;
        }
        assertTrue(hits > 30 && misses > 30, "mix hits=" + hits + " misses=" + misses);
    }
}
