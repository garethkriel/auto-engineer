package ae.spatial;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GeometryTest {
    @Test
    void pointRejectsNonFinite() {
        assertThrows(IllegalArgumentException.class, () -> new Point(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Point(0, Double.POSITIVE_INFINITY));
    }

    @Test
    void rectRejectsInvertedAndNonFinite() {
        assertThrows(IllegalArgumentException.class, () -> new Rect(1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, Double.NaN, 1));
    }

    @Test
    void rectContainsIsClosed() {
        Rect r = new Rect(0, 0, 2, 2);
        assertTrue(r.contains(new Point(0, 0)));
        assertTrue(r.contains(new Point(2, 2)));
        assertTrue(r.contains(new Point(1, 2)));
        assertFalse(r.contains(new Point(2.0000001, 1)));
        assertFalse(r.contains(new Point(1, -0.0000001)));
    }

    @Test
    void rectIntersectsTouchingEdgesAndCorners() {
        Rect r = new Rect(0, 0, 2, 2);
        assertTrue(r.intersects(new Rect(2, 0, 3, 1)));
        assertTrue(r.intersects(new Rect(2, 2, 3, 3)));
        assertTrue(r.intersects(new Rect(-5, -5, 5, 5)));
        assertTrue(r.intersects(new Rect(0.5, 0.5, 1, 1)));
        assertFalse(r.intersects(new Rect(2.5, 0, 3, 1)));
        assertFalse(r.intersects(new Rect(0, -3, 2, -1)));
    }

    @Test
    void distanceSquaredIsZeroInsideAndEuclideanOutside() {
        Rect r = new Rect(0, 0, 4, 4);
        assertEquals(0.0, r.distanceSquared(new Point(2, 2)));
        assertEquals(0.0, r.distanceSquared(new Point(4, 4)));
        assertEquals(9.0, r.distanceSquared(new Point(7, 2)));
        assertEquals(25.0, r.distanceSquared(new Point(7, 8)));
        assertEquals(1.0, r.distanceSquared(new Point(2, -1)));
    }
}
