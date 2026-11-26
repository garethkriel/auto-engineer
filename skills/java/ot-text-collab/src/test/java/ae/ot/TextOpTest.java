package ae.ot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TextOpTest {

    private static TextOp op(Object... parts) {
        TextOp.Builder b = TextOp.builder();
        for (Object p : parts) {
            if (p instanceof Integer n) {
                if (n >= 0) b.retain(n); else b.delete(-n);
            } else {
                b.insert((String) p);
            }
        }
        return b.build();
    }

    @Test
    void builderDropsZeroLengthParts() {
        assertEquals(op(3), op(0, 3, 0, ""));
        assertEquals(0, TextOp.builder().build().baseLength());
    }

    @Test
    void builderMergesAdjacentParts() {
        TextOp a = TextOp.builder().retain(1).retain(2).insert("a").insert("b").delete(1).delete(2).build();
        assertEquals("[R3, I\"ab\", D3]", a.toString());
        assertEquals(6, a.baseLength());
        assertEquals(5, a.targetLength());
    }

    @Test
    void insertIsStoredBeforeAdjacentDelete() {
        TextOp a = TextOp.builder().retain(1).delete(2).insert("xy").retain(1).build();
        TextOp b = TextOp.builder().retain(1).insert("xy").delete(2).retain(1).build();
        assertEquals(b, a);
        assertEquals(b.hashCode(), a.hashCode());
        assertEquals("[R1, I\"xy\", D2, R1]", a.toString());
    }

    @Test
    void insertAfterDeleteMergesIntoEarlierInsert() {
        TextOp a = TextOp.builder().insert("a").delete(2).insert("b").build();
        assertEquals("[I\"ab\", D2]", a.toString());
    }

    @Test
    void builderRejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> TextOp.builder().retain(-1));
        assertThrows(IllegalArgumentException.class, () -> TextOp.builder().delete(-1));
        assertThrows(NullPointerException.class, () -> TextOp.builder().insert(null));
    }

    @Test
    void applyLiteral() {
        // "hello world": keep "hello", delete " world", insert "!" -> "hello!"
        assertEquals("hello!", op(5, "!", -6).apply("hello world"));
        assertEquals("XabcY", op("X", 3, "Y").apply("abc"));
        assertEquals("", op(-3).apply("abc"));
        assertEquals("abc", op("abc").apply(""));
    }

    @Test
    void applyChecksLength() {
        TextOp o = op(3);
        assertThrows(IllegalArgumentException.class, () -> o.apply("ab"));
        assertThrows(IllegalArgumentException.class, () -> o.apply("abcd"));
    }

    @Test
    void noopDetection() {
        assertTrue(op(4).isNoop());
        assertTrue(TextOp.builder().build().isNoop());
        assertFalse(op(1, "x", 2).isNoop());
        assertFalse(op(1, -1, 2).isNoop());
        assertEquals(op(4), TextOp.identity(4));
    }

    @Test
    void composeLiteral() {
        // "abc" -> "aXbc" -> "aXc"
        TextOp a = op(1, "X", 2);
        TextOp b = op(2, -1, 1);
        assertEquals(op(1, "X", -1, 1), a.compose(b));
        // insert then delete of the same text cancels
        assertEquals(op(3), op(1, "X", 2).compose(op(1, -1, 2)));
        // delete then insert at the same place becomes insert before delete
        assertEquals(op("Z", -1, 2), op(-1, 2).compose(op("Z", 2)));
    }

    @Test
    void composeLengthMismatchThrows() {
        assertThrows(IllegalArgumentException.class, () -> op(3).compose(op(4)));
        assertThrows(IllegalArgumentException.class, () -> op(1, "xx").compose(op(2)));
    }

    @Test
    void composeEqualsSequentialApplyAndIsAssociative() {
        Random rng = new Random(11);
        for (int i = 0; i < 2000; i++) {
            String doc = RandomOps.randomText(rng, rng.nextInt(12), "abcdefgh");
            TextOp a = RandomOps.generate(rng, doc, "xyz");
            String d1 = a.apply(doc);
            TextOp b = RandomOps.generate(rng, d1, "uvw");
            String d2 = b.apply(d1);
            TextOp c = RandomOps.generate(rng, d2, "pq");
            String d3 = c.apply(d2);
            assertEquals(d2, a.compose(b).apply(doc), "compose(a,b) on '" + doc + "' " + a + " " + b);
            assertEquals(d3, a.compose(b).compose(c).apply(doc));
            assertEquals(a.compose(b).compose(c), a.compose(b.compose(c)), a + " " + b + " " + c);
            assertEquals(doc.length(), a.compose(b).baseLength());
            assertEquals(d2.length(), a.compose(b).targetLength());
        }
    }

    @Test
    void composeWithIdentityIsNeutral() {
        Random rng = new Random(5);
        for (int i = 0; i < 300; i++) {
            String doc = RandomOps.randomText(rng, rng.nextInt(10), "abc");
            TextOp a = RandomOps.generate(rng, doc, "xy");
            assertEquals(a, TextOp.identity(a.baseLength()).compose(a));
            assertEquals(a, a.compose(TextOp.identity(a.targetLength())));
        }
    }

    @Test
    void invertLiteral() {
        TextOp a = op(1, "XY", -2, 1);
        assertEquals("aXYd", a.apply("abcd"));
        assertEquals(op(1, -2, "bc", 1), a.invert("abcd"));
    }

    @Test
    void invertRoundTripsAndComposesToNoop() {
        Random rng = new Random(23);
        for (int i = 0; i < 1000; i++) {
            String doc = RandomOps.randomText(rng, rng.nextInt(12), "abcdef");
            TextOp a = RandomOps.generate(rng, doc, "xyz");
            TextOp inv = a.invert(doc);
            assertEquals(doc, inv.apply(a.apply(doc)));
            assertEquals(doc, a.compose(inv).apply(doc), a + " then " + inv);   // delete+reinsert of equal text is not a canonical no-op
            assertEquals(a, inv.invert(a.apply(doc)));
        }
    }

    @Test
    void invertRejectsWrongDocument() {
        assertThrows(IllegalArgumentException.class, () -> op(1, -2).invert("ab"));
    }

    @Test
    void transformIndexTable() {
        // base "abcde": keep "ab", insert "XY", delete "cd", keep "e"  -> "abXYe"
        TextOp o = op(2, "XY", -2, 1);
        // index in base : 0 1 2 3 4 5
        int[] stay = {0, 1, 2, 4, 4, 5};   // cursor stays before the insert at index 2
        int[] adv = {0, 1, 4, 4, 4, 5};    // cursor moves after the insert at index 2
        for (int i = 0; i <= 5; i++) {
            assertEquals(stay[i], o.transformIndex(i, false), "stay " + i);
            assertEquals(adv[i], o.transformIndex(i, true), "advance " + i);
        }
    }

    @Test
    void transformIndexInsertAtEnd() {
        TextOp o = op(3, "Z");
        assertEquals(3, o.transformIndex(3, false));
        assertEquals(4, o.transformIndex(3, true));
        assertEquals(1, o.transformIndex(1, true));
    }

    @Test
    void transformIndexRejectsOutOfRange() {
        TextOp o = op(3);
        assertThrows(IllegalArgumentException.class, () -> o.transformIndex(-1, false));
        assertThrows(IllegalArgumentException.class, () -> o.transformIndex(4, false));
    }

    @Test
    void transformIndexTracksSurvivingCharacters() {
        Random rng = new Random(77);
        int checked = 0;
        for (int i = 0; i < 1000; i++) {
            String doc = "abcdefghij".substring(0, 1 + rng.nextInt(10));
            TextOp a = RandomOps.generate(rng, doc, "XYZ");   // inserted chars are upper case, so never confused with doc chars
            String after = a.apply(doc);
            for (int p = 0; p < doc.length(); p++) {
                int at = after.indexOf(doc.charAt(p));
                if (at < 0) continue;   // deleted
                // the gap just before a surviving char, moving past inserts at that gap, is right before it
                assertEquals(at, a.transformIndex(p, true), doc + " " + a + " gap before " + doc.charAt(p));
                // the gap just after it, staying before inserts at that gap, is right after it
                assertEquals(at + 1, a.transformIndex(p + 1, false), doc + " " + a + " gap after " + doc.charAt(p));
                checked++;
            }
        }
        assertTrue(checked > 3000, "checked " + checked);
    }

    @Test
    void componentsAreImmutableView() {
        TextOp o = op(1, "x");
        assertThrows(UnsupportedOperationException.class, () -> o.components().add(new TextOp.Retain(1)));
    }
}
