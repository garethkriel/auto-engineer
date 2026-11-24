package ae.rope;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RopeTest {

    /** 102 chars (20+20+20+20+22), longer than three leaves, so every op crosses leaf boundaries. */
    static final String LONG =
            "0123456789abcdefghij0123456789ABCDEFGHIJ0123456789klmnopqrst0123456789KLMNOPQRST0123456789uvwxyzUVWXYZ";

    @Test
    void emptyRope() {
        Rope e = Rope.empty();
        assertEquals(0, e.length());
        assertEquals("", e.toString());
        assertEquals(Rope.of(""), e);
        assertEquals(0, e.depth());
        e.check();
        assertThrows(IndexOutOfBoundsException.class, () -> e.charAt(0));
    }

    @Test
    void ofRoundTripsAndIndexesEveryChar() {
        Rope r = Rope.of(LONG);
        assertEquals(LONG.length(), r.length());
        assertEquals(LONG, r.toString());
        for (int i = 0; i < LONG.length(); i++) {
            assertEquals(LONG.charAt(i), r.charAt(i), "index " + i);
        }
        assertTrue(r.depth() >= 2);
        r.check();
    }

    @Test
    void charAtRejectsOutOfRange() {
        Rope r = Rope.of("abc");
        assertThrows(IndexOutOfBoundsException.class, () -> r.charAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> r.charAt(3));
    }

    @Test
    void concatLiteral() {
        Rope r = Rope.of("hello ").concat(Rope.of("world"));
        assertEquals("hello world", r.toString());
        assertEquals(11, r.length());
        assertEquals("abc", Rope.empty().concat(Rope.of("abc")).toString());
        assertEquals("abc", Rope.of("abc").concat(Rope.empty()).toString());
    }

    @Test
    void concatOfTwoLongRopes() {
        Rope r = Rope.of(LONG).concat(Rope.of(LONG));
        assertEquals(LONG + LONG, r.toString());
        r.check();
    }

    @Test
    void insertAtStartMiddleEnd() {
        assertEquals("XYabc", Rope.of("abc").insert(0, "XY").toString());
        assertEquals("aXYbc", Rope.of("abc").insert(1, "XY").toString());
        assertEquals("abcXY", Rope.of("abc").insert(3, "XY").toString());
        String expected = LONG.substring(0, 33) + "<<>>" + LONG.substring(33);
        assertEquals(expected, Rope.of(LONG).insert(33, "<<>>").toString());
        assertThrows(IndexOutOfBoundsException.class, () -> Rope.of("abc").insert(4, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> Rope.of("abc").insert(-1, "x"));
    }

    @Test
    void deleteRanges() {
        assertEquals("ac", Rope.of("abc").delete(1, 2).toString());
        assertEquals("abc", Rope.of("abc").delete(1, 1).toString());
        assertEquals("", Rope.of("abc").delete(0, 3).toString());
        assertEquals(LONG.substring(0, 20) + LONG.substring(70), Rope.of(LONG).delete(20, 70).toString());
        assertThrows(IndexOutOfBoundsException.class, () -> Rope.of("abc").delete(2, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> Rope.of("abc").delete(0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> Rope.of("abc").delete(-1, 1));
    }

    @Test
    void replaceRange() {
        assertEquals("aXc", Rope.of("abc").replace(1, 2, "X").toString());
        assertEquals("aXYZc", Rope.of("abc").replace(1, 2, "XYZ").toString());
        assertEquals("ac", Rope.of("abc").replace(1, 2, "").toString());
        assertEquals("abcd", Rope.of("abc").replace(3, 3, "d").toString());
    }

    @Test
    void splitAtEveryBoundary() {
        Rope r = Rope.of(LONG);
        for (int i = 0; i <= LONG.length(); i++) {
            Rope.Split s = r.split(i);
            assertEquals(LONG.substring(0, i), s.left().toString(), "left at " + i);
            assertEquals(LONG.substring(i), s.right().toString(), "right at " + i);
            s.left().check();
            s.right().check();
        }
        assertThrows(IndexOutOfBoundsException.class, () -> r.split(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> r.split(LONG.length() + 1));
    }

    @Test
    void substringAndSubSequence() {
        Rope r = Rope.of(LONG);
        assertEquals(LONG.substring(31, 66), r.substring(31, 66));
        assertEquals("", r.substring(40, 40));
        CharSequence cs = r;
        assertEquals(LONG.substring(5, 50), cs.subSequence(5, 50).toString());
        assertTrue(r.subSequence(5, 50) instanceof Rope);
        assertThrows(IndexOutOfBoundsException.class, () -> r.substring(5, LONG.length() + 1));
        assertThrows(IndexOutOfBoundsException.class, () -> r.substring(6, 5));
    }

    @Test
    void equalsAndHashCodeIgnoreTreeShape() {
        Rope whole = Rope.of(LONG);
        Rope pieced = Rope.empty();
        for (int i = 0; i < LONG.length(); i += 7) {
            pieced = pieced.concat(Rope.of(LONG.substring(i, Math.min(LONG.length(), i + 7))));
        }
        assertEquals(whole, pieced);
        assertEquals(whole.hashCode(), pieced.hashCode());
        assertEquals(LONG.hashCode(), whole.hashCode());
        assertNotEquals(whole, pieced.insert(50, "x"));
        assertNotEquals(whole, Rope.of(LONG.substring(0, 99)));
        assertNotEquals(whole, LONG, "a Rope never equals a String");
        assertNotEquals(whole, null);
    }

    @Test
    void nullArgumentsAreRejected() {
        Rope r = Rope.of("abc");
        assertThrows(NullPointerException.class, () -> Rope.of(null));
        assertThrows(NullPointerException.class, () -> r.concat(null));
        assertThrows(NullPointerException.class, () -> r.insert(0, null));
        assertThrows(NullPointerException.class, () -> r.replace(0, 1, null));
        assertThrows(NullPointerException.class, () -> r.indexOf(null));
    }

    @Test
    void oldVersionsNeverChange() {
        Rope v0 = Rope.of(LONG);
        Rope v1 = v0.insert(50, "[INS]");
        Rope v2 = v1.delete(0, 10);
        Rope v3 = v2.replace(3, 60, "-");
        Rope v4 = v0.concat(v3);
        assertEquals(LONG, v0.toString());
        assertEquals(LONG.substring(0, 50) + "[INS]" + LONG.substring(50), v1.toString());
        assertEquals(v1.toString().substring(10), v2.toString());
        assertEquals(v2.toString().substring(0, 3) + "-" + v2.toString().substring(60), v3.toString());
        assertEquals(LONG + v3, v4.toString());
        for (Rope r : new Rope[] {v0, v1, v2, v3, v4}) {
            r.check();
        }
    }

    @Test
    void ofCharSequenceAcceptsStringBuilderAndRope() {
        assertEquals("xyz", Rope.of(new StringBuilder("xyz")).toString());
        Rope r = Rope.of(LONG);
        assertEquals(LONG, Rope.of(r).toString());
        assertEquals("ab" + LONG + "cd", Rope.of("ab").concat(r).concat(Rope.of("cd")).toString());
        assertEquals("a" + LONG + "bc", Rope.of("abc").insert(1, r).toString());
    }
}
