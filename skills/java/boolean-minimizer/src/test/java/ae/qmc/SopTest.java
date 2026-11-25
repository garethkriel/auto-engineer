package ae.qmc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SopTest {
    @Test
    void evalAndCost() {
        Sop s = Sop.parse(3, "A'B + C");
        for (int m = 0; m < 8; m++) {
            boolean a = (m & 4) != 0, b = (m & 2) != 0, c = (m & 1) != 0;
            assertEquals(!a && b || c, s.eval(m), "m=" + m);
        }
        assertEquals(2, s.termCount());
        assertEquals(3, s.literalCount(3));
    }

    @Test
    void constants() {
        Sop zero = Sop.parse(3, "0");
        Sop one = Sop.parse(3, "1");
        assertEquals("0", zero.toString());
        assertEquals("1", one.toString());
        for (int m = 0; m < 8; m++) {
            assertFalse(zero.eval(m));
            assertTrue(one.eval(m));
        }
        assertEquals(0, zero.termCount());
        assertEquals(1, one.termCount());
        assertEquals(0, one.literalCount(3));
    }

    @Test
    void parseRoundTripAndNormalisation() {
        Sop s = Sop.parse(4, " BC'D'+AB' + AB' ");
        assertEquals("BC'D' + AB'", s.toString()); // canonical order: value 0100 before 1000
        assertEquals(s, Sop.parse(4, s.toString()));
        assertEquals(2, s.termCount()); // duplicate term dropped
    }

    @Test
    void parseRejectsBadText() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Sop.parse(3, "D"));
        assertTrue(e.getMessage().contains("bad variable"), e.getMessage()); // 'D' is the first letter beyond n=3
        assertEquals("C", Sop.parse(3, "C").toString()); // control: the last legal letter
        assertThrows(IllegalArgumentException.class, () -> Sop.parse(3, "AA"));
        assertThrows(IllegalArgumentException.class, () -> Sop.parse(3, "AA'"));
        assertThrows(IllegalArgumentException.class, () -> Sop.parse(3, "A + "));
        assertThrows(IllegalArgumentException.class, () -> Sop.parse(3, "'A"));
        assertThrows(IllegalArgumentException.class, () -> Sop.parse(3, "a"));
        assertThrows(IllegalArgumentException.class, () -> Sop.parse(3, ""));
        assertThrows(IllegalArgumentException.class, () -> Sop.parse(0, "0"));
    }

    @Test
    void termsAreImmutableView() {
        Sop s = Sop.parse(2, "A");
        List<Implicant> t = s.terms();
        assertThrows(UnsupportedOperationException.class, () -> t.add(new Implicant(0, 0)));
    }
}
