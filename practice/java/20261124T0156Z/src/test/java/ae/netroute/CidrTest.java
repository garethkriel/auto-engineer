package ae.netroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class CidrTest {
    @Test
    void parseBoundsAndText() {
        Cidr c = Cidr.parse("10.1.2.0/24");
        assertEquals(Ip4.parse("10.1.2.0"), c.first());
        assertEquals(Ip4.parse("10.1.2.255"), c.last());
        assertEquals(256, c.size());
        assertEquals("10.1.2.0/24", c.toString());
        assertEquals(1L << 32, Cidr.parse("0.0.0.0/0").size());
        assertEquals(0xFFFFFFFFL, Cidr.parse("0.0.0.0/0").last());
        assertEquals(1, Cidr.parse("9.9.9.9/32").size());
        assertEquals(Ip4.parse("9.9.9.9"), Cidr.parse("9.9.9.9/32").last());
    }

    @Test
    void hostBitsAreAnError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.1.2.1/24"));
        assertTrue(e.getMessage().contains("host bits set"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("128.0.0.0/0"));
        assertDoesNotThrow(() -> Cidr.parse("10.1.2.0/24")); // control: same text without the host bit
    }

    @Test
    void rejectsBadSyntax() {
        for (String s : new String[] {"10.0.0.0", "10.0.0.0/", "10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/024",
            "10.0.0.0/2x", "/8", "10.0.0/8", "10.0.0.0/8/8", "10.0.0.0/+8"}) {
            assertThrows(IllegalArgumentException.class, () -> Cidr.parse(s), s);
        }
    }

    @Test
    void containingMasks() {
        assertEquals(Cidr.parse("10.1.2.0/24"), Cidr.containing(Ip4.parse("10.1.2.77"), 24));
        assertEquals(Cidr.parse("0.0.0.0/0"), Cidr.containing(Ip4.parse("10.1.2.77"), 0));
        assertEquals(Cidr.parse("10.1.2.77/32"), Cidr.containing(Ip4.parse("10.1.2.77"), 32));
    }

    @Test
    void containmentAndOverlap() {
        Cidr a = Cidr.parse("10.0.0.0/8");
        Cidr b = Cidr.parse("10.5.0.0/16");
        Cidr c = Cidr.parse("11.0.0.0/8");
        assertTrue(a.contains(b));
        assertFalse(b.contains(a));
        assertTrue(a.contains(a));
        assertTrue(a.overlaps(b));
        assertTrue(b.overlaps(a));
        assertFalse(a.overlaps(c));
        assertTrue(a.contains(Ip4.parse("10.255.255.255")));
        assertFalse(a.contains(Ip4.parse("11.0.0.0")));
        assertFalse(a.contains(Ip4.parse("9.255.255.255")));
    }

    @Test
    void naturalOrderIsBaseThenLength() {
        List<Cidr> l = new ArrayList<>(List.of(Cidr.parse("10.0.0.0/16"), Cidr.parse("9.0.0.0/8"),
            Cidr.parse("10.0.0.0/8"), Cidr.parse("10.0.0.0/24")));
        Collections.sort(l);
        assertEquals(List.of("9.0.0.0/8", "10.0.0.0/8", "10.0.0.0/16", "10.0.0.0/24"),
            l.stream().map(Cidr::toString).toList());
    }
}
