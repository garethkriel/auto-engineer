package ae.tsblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class PointTest {
    @Test
    void valueSemantics() {
        assertEquals(new Point(1, 2), new Point(1, 2));
        assertNotEquals(new Point(1, 2), new Point(2, 1));
        assertEquals("Point[ts=1, value=2]", new Point(1, 2).toString());
    }
}
