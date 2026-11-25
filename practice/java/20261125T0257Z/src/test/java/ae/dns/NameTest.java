package ae.dns;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NameTest {
    @Test
    void parseNormalisesCaseAndTrailingDot() {
        Name a = Name.parse("WWW.Example.COM.");
        Name b = Name.parse("www.example.com");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("www.example.com.", a.toString());
        assertEquals(List.of("www", "example", "com"), a.labels());
        assertEquals(3, a.labelCount());
    }

    @Test
    void rootIsDotAndHasNoLabels() {
        Name root = Name.parse(".");
        assertSame(Name.ROOT, root);
        assertTrue(root.isRoot());
        assertEquals(".", root.toString());
        assertEquals(0, root.labelCount());
        assertEquals(1, root.wireLength());
        assertThrows(IllegalStateException.class, root::parent);
    }

    @Test
    void parentAndChild() {
        Name n = Name.parse("a.b.c");
        assertEquals(Name.parse("b.c"), n.parent());
        assertEquals(Name.parse("x.a.b.c"), n.child("X"));
        assertEquals(Name.parse("c"), n.parent().parent());
        assertEquals(Name.ROOT, n.parent().parent().parent());
    }

    @Test
    void subdomainIsReflexiveAndLabelWise() {
        Name zone = Name.parse("example.com");
        assertTrue(Name.parse("example.com").isSubdomainOf(zone));
        assertTrue(Name.parse("a.b.example.com").isSubdomainOf(zone));
        assertFalse(Name.parse("com").isSubdomainOf(zone));
        // label-wise, not string suffix: "badexample.com" ends with "example.com" as text only
        assertFalse(Name.parse("badexample.com").isSubdomainOf(zone));
        assertTrue(zone.isSubdomainOf(Name.ROOT));
        assertFalse(Name.ROOT.isSubdomainOf(zone));
    }

    @Test
    void invalidNamesAreRejected() {
        for (String bad : new String[] {"", "a..b", "..", ".a", "a b.c", "aé.c"}) {
            assertThrows(IllegalArgumentException.class, () -> Name.parse(bad), "should reject [" + bad + "]");
        }
        assertThrows(NullPointerException.class, () -> Name.parse(null));
    }

    @Test
    void labelLengthBoundary63() {
        String ok = "a".repeat(63);
        assertEquals(63, Name.parse(ok + ".com").labels().get(0).length());
        assertThrows(IllegalArgumentException.class, () -> Name.parse("a".repeat(64) + ".com"));
    }

    @Test
    void wireLengthBoundary255() {
        // three 63-char labels = 3*64 = 192, root byte = 193; a final label of L chars adds L+1.
        String l63 = "b".repeat(63);
        String prefix = l63 + "." + l63 + "." + l63 + ".";
        Name ok = Name.parse(prefix + "c".repeat(61)); // 193 + 62 = 255
        assertEquals(255, ok.wireLength());
        assertThrows(IllegalArgumentException.class, () -> Name.parse(prefix + "c".repeat(62))); // 256
        assertThrows(IllegalArgumentException.class, () -> ok.child("x")); // child pushes it over
    }

    @Test
    void wireLengthOfSimpleName() {
        // 3www 7example 3com root = 4 + 8 + 4 + 1
        assertEquals(17, Name.parse("www.example.com").wireLength());
    }
}
