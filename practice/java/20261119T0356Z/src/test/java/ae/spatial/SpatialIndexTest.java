package ae.spatial;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpatialIndexTest {
    private static List<String> ids(List<Entry> es) {
        List<String> out = new ArrayList<>();
        for (Entry e : es) {
            out.add(e.id());
        }
        return out;
    }

    private static SpatialIndex grid(int n) {
        SpatialIndex ix = new SpatialIndex();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                ix.insert("p" + i + "_" + j, i, j);
            }
        }
        return ix;
    }

    @Test
    void emptyIndexAnswersEmpty() {
        SpatialIndex ix = new SpatialIndex();
        assertEquals(0, ix.size());
        assertTrue(ix.range(new Rect(-1, -1, 1, 1)).isEmpty());
        assertTrue(ix.nearest(0, 0, 3).isEmpty());
        assertTrue(ix.radius(0, 0, 10).isEmpty());
        assertEquals(Optional.empty(), ix.get("a"));
        assertFalse(ix.remove("a"));
        ix.checkInvariants();
    }

    @Test
    void insertGetAndDuplicateRejected() {
        SpatialIndex ix = new SpatialIndex();
        ix.insert("a", 1, 2);
        assertEquals(Optional.of(new Point(1, 2)), ix.get("a"));
        assertThrows(IllegalArgumentException.class, () -> ix.insert("a", 5, 5));
        assertEquals(new Point(1, 2), ix.get("a").orElseThrow());
        assertEquals(1, ix.size());
    }

    @Test
    void invalidArgumentsRejectedWithoutChange() {
        SpatialIndex ix = new SpatialIndex();
        assertThrows(IllegalArgumentException.class, () -> ix.insert(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ix.insert("", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ix.insert("a", Double.NaN, 0));
        assertEquals(0, ix.size());
        assertThrows(IllegalArgumentException.class, () -> ix.radius(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> ix.nearest(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> ix.inPolygon(List.of(new Point(0, 0), new Point(1, 1))));
        assertThrows(NullPointerException.class, () -> ix.range(null));
    }

    @Test
    void rangeIsClosedAndSortedById() {
        SpatialIndex ix = grid(5);
        List<String> got = ids(ix.range(new Rect(1, 1, 2, 2)));
        assertEquals(List.of("p1_1", "p1_2", "p2_1", "p2_2"), got);
        assertEquals(List.of("p4_4"), ids(ix.range(new Rect(4, 4, 9, 9))));
        assertEquals(List.of(), ids(ix.range(new Rect(0.1, 0.1, 0.9, 0.9))));
    }

    @Test
    void pointOnMidlineIsFoundAfterSplit() {
        SpatialIndex ix = new SpatialIndex();
        for (int i = 0; i < 9; i++) {
            ix.insert("k" + i, i, i);
        }
        ix.checkInvariants();
        for (int i = 0; i < 9; i++) {
            assertEquals(List.of("k" + i), ids(ix.range(new Rect(i, i, i, i))));
        }
    }

    @Test
    void radiusIsClosedDiskSortedByDistanceThenId() {
        SpatialIndex ix = new SpatialIndex();
        ix.insert("e", 3, 4);   // distance 5 exactly
        ix.insert("b", 0, 1);
        ix.insert("a", 1, 0);   // ties with b at distance 1
        ix.insert("far", 3.0001, 4);
        ix.insert("o", 0, 0);
        assertEquals(List.of("o", "a", "b", "e"), ids(ix.radius(0, 0, 5)));
        assertEquals(List.of("o"), ids(ix.radius(0, 0, 0)));
    }

    @Test
    void nearestOrdersByDistanceThenIdAcrossCells() {
        SpatialIndex ix = new SpatialIndex();
        // 20 points on a circle-ish ring of equal distance 10 around (0,0) in different quadrants.
        int[][] pts = {{10, 0}, {-10, 0}, {0, 10}, {0, -10}, {6, 8}, {-6, 8}, {6, -8}, {-6, -8},
                       {8, 6}, {-8, 6}, {8, -6}, {-8, -6}};
        for (int i = 0; i < pts.length; i++) {
            ix.insert(String.format("r%02d", pts.length - i), pts[i][0], pts[i][1]);
        }
        ix.insert("center", 0, 0);
        ix.insert("x", 50, 50);
        List<String> got = ids(ix.nearest(0, 0, 5));
        assertEquals(List.of("center", "r01", "r02", "r03", "r04"), got);
        assertEquals(14, ix.nearest(0, 0, 100).size());
        assertEquals(List.of(), ids(ix.nearest(0, 0, 0)));
        assertEquals("x", ix.nearest(0, 0, 100).get(13).id());
    }

    @Test
    void nearestFromQueryOutsideBounds() {
        SpatialIndex ix = grid(4);
        assertEquals(List.of("p0_0"), ids(ix.nearest(-100, -100, 1)));
        assertEquals(List.of("p3_3"), ids(ix.nearest(100, 100, 1)));
    }

    @Test
    void polygonTriangleBoundaryAndOutside() {
        SpatialIndex ix = new SpatialIndex();
        ix.insert("inside", 1, 1);
        ix.insert("vertex", 0, 0);
        ix.insert("edge", 2, 0);
        ix.insert("hyp", 2, 2);       // on the hypotenuse of (0,0),(4,0),(0,4)? 2+2=4 yes
        ix.insert("outside", 3, 3);
        ix.insert("beyond", -1, 1);
        ix.insert("collinearOutside", 5, 0); // on line of an edge but beyond the segment
        List<Point> tri = List.of(new Point(0, 0), new Point(4, 0), new Point(0, 4));
        assertEquals(List.of("edge", "hyp", "inside", "vertex"), ids(ix.inPolygon(tri)));
    }

    @Test
    void polygonConcaveNotchExcluded() {
        // U shape: outer 0..6 x 0..6 with a notch x in 2..4, y in 2..6 removed.
        List<Point> u = List.of(new Point(0, 0), new Point(6, 0), new Point(6, 6), new Point(4, 6),
                new Point(4, 2), new Point(2, 2), new Point(2, 6), new Point(0, 6));
        SpatialIndex ix = new SpatialIndex();
        ix.insert("leftArm", 1, 5);
        ix.insert("rightArm", 5, 5);
        ix.insert("bottom", 3, 1);
        ix.insert("notch", 3, 4);
        ix.insert("notchFloor", 3, 2);
        ix.insert("notchWall", 2, 4);
        assertEquals(List.of("bottom", "leftArm", "notchFloor", "notchWall", "rightArm"), ids(ix.inPolygon(u)));
    }

    @Test
    void moveIsAtomicAndRelocates() {
        SpatialIndex ix = grid(3);
        ix.move("p0_0", 100, 100);
        assertEquals(List.of("p0_0"), ids(ix.range(new Rect(99, 99, 101, 101))));
        assertEquals(9, ix.size());
        ix.checkInvariants();
        assertThrows(IllegalArgumentException.class, () -> ix.move("nope", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> ix.move("p1_1", Double.NaN, 1));
        assertEquals(new Point(1, 1), ix.get("p1_1").orElseThrow());
        assertEquals(9, ix.size());
    }

    @Test
    void growingOutwardKeepsEverythingFindable() {
        SpatialIndex ix = new SpatialIndex();
        double[] mags = {0.5, 3, 40, 900, 12345, 1e7, -1e7, -3e9};
        int n = 0;
        for (double m : mags) {
            ix.insert("a" + n, m, -m / 2);
            ix.insert("b" + n, -m, m);
            n++;
            ix.checkInvariants();
        }
        assertEquals(16, ix.size());
        assertEquals(16, ix.range(new Rect(-1e10, -1e10, 1e10, 1e10)).size());
        assertEquals(List.of("a3"), ids(ix.range(new Rect(899, -451, 901, -449))));
    }

    @Test
    void coincidentPointsBeyondCapacityStayRetrievable() {
        SpatialIndex ix = new SpatialIndex();
        for (int i = 0; i < 40; i++) {
            ix.insert(String.format("c%02d", i), 7.5, 7.5);
        }
        ix.insert("other", 100, 100);
        ix.checkInvariants();
        assertEquals(40, ix.range(new Rect(7, 7, 8, 8)).size());
        assertEquals(List.of("c00", "c01", "c02"), ids(ix.nearest(7.5, 7.5, 3)));
        for (int i = 0; i < 40; i++) {
            assertTrue(ix.remove(String.format("c%02d", i)));
        }
        ix.checkInvariants();
        assertEquals(1, ix.size());
    }

    @Test
    void removalCollapsesTreeBackToSingleLeaf() {
        SpatialIndex ix = grid(6);
        ix.checkInvariants();
        assertTrue(ix.depth() >= 1);
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                if (i * 6 + j >= 8) {
                    assertTrue(ix.remove("p" + i + "_" + j));
                    ix.checkInvariants();
                }
            }
        }
        assertEquals(8, ix.size());
        assertEquals(0, ix.depth());
        assertFalse(ix.remove("p5_5"));
    }
}
