package ae.ot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TextOpTest {

    static TextOp randomOp(String doc, Random r) {
        TextOp.Builder b = new TextOp.Builder();
        int i = 0;
        while (i < doc.length()) {
            int n = Math.min(1 + r.nextInt(3), doc.length() - i);
            switch (r.nextInt(3)) {
                case 0 -> { b.retain(n); i += n; }
                case 1 -> { b.delete(n); i += n; }
                default -> b.insert(randomText(r));
            }
        }
        if (r.nextInt(3) == 0) b.insert(randomText(r));
        return b.build();
    }

    static String randomText(Random r) {
        int n = 1 + r.nextInt(3);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) sb.append((char) ('a' + r.nextInt(26)));
        return sb.toString();
    }

    static String randomDoc(Random r) {
        int n = r.nextInt(9);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) sb.append((char) ('A' + r.nextInt(26)));
        return sb.toString();
    }

    @Test
    void builderNormalises() {
        TextOp op = new TextOp.Builder().retain(0).retain(2).retain(1).delete(1).insert("x").insert("y").delete(2).build();
        // retain 3, then insert "xy" moved before the merged delete of 3
        assertEquals("[R3, I\"xy\", D3]", op.toString());
        assertEquals(6, op.baseLength());
        assertEquals(5, op.targetLength());
    }

    @Test
    void insertAfterDeleteSwapsToInsertFirst() {
        TextOp a = new TextOp.Builder().delete(2).insert("z").build();
        TextOp b = new TextOp.Builder().insert("z").delete(2).build();
        assertEquals(a, b);
        assertEquals("[I\"z\", D2]", a.toString());
    }

    @Test
    void applyLiteral() {
        TextOp op = new TextOp.Builder().retain(1).delete(2).insert("XY").retain(1).build();
        assertEquals("aXYd", op.apply("abcd"));
    }

    @Test
    void applyRejectsWrongLengthWithMessage() {
        TextOp op = new TextOp.Builder().retain(3).build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> op.apply("ab"));
        assertTrue(e.getMessage().startsWith("base length"), e.getMessage());
    }

    @Test
    void builderRejectsNegativeAndNull() {
        assertThrows(IllegalArgumentException.class, () -> new TextOp.Builder().retain(-1));
        assertThrows(IllegalArgumentException.class, () -> new TextOp.Builder().delete(-1));
        assertThrows(NullPointerException.class, () -> new TextOp.Builder().insert(null));
    }

    @Test
    void composeLiteral() {
        TextOp a = new TextOp.Builder().retain(2).insert("XY").retain(2).build(); // abcd -> abXYcd
        TextOp b = new TextOp.Builder().retain(3).delete(2).retain(1).build();    // abXYcd -> abXd
        TextOp c = TextOp.compose(a, b);
        assertEquals("abXd", c.apply("abcd"));
        assertEquals("[R2, I\"X\", D1, R1]", c.toString());
    }

    @Test
    void composeInsertThenDeleteCancels() {
        TextOp a = new TextOp.Builder().insert("hi").retain(1).build();
        TextOp b = new TextOp.Builder().delete(2).retain(1).build();
        assertEquals("[R1]", TextOp.compose(a, b).toString());
    }

    @Test
    void composeRejectsMismatchWithMessage() {
        TextOp a = new TextOp.Builder().retain(2).build();
        TextOp b = new TextOp.Builder().retain(3).build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TextOp.compose(a, b));
        assertTrue(e.getMessage().startsWith("compose length mismatch"), e.getMessage());
    }

    @Test
    void transformTieInsertsFirstArgumentFirst() {
        TextOp a = new TextOp.Builder().insert("A").retain(2).build();
        TextOp b = new TextOp.Builder().insert("B").retain(2).build();
        TextOp.Pair p = TextOp.transform(a, b);
        assertEquals("ABxy", p.second().apply(a.apply("xy")));
        assertEquals("ABxy", p.first().apply(b.apply("xy")));
        TextOp.Pair q = TextOp.transform(b, a);
        assertEquals("BAxy", q.second().apply(b.apply("xy")));
    }

    @Test
    void transformInsertInsideDeletedRange() {
        TextOp a = new TextOp.Builder().retain(1).delete(3).build();                  // abcd -> a
        TextOp b = new TextOp.Builder().retain(2).insert("Z").retain(2).build();      // abcd -> abZcd
        TextOp.Pair p = TextOp.transform(a, b);
        // delete survives around the insert; insert survives the delete: a Z
        assertEquals("aZ", p.second().apply("a"));
        assertEquals("aZ", p.first().apply("abZcd"));
    }

    @Test
    void transformOverlappingDeletes() {
        TextOp a = new TextOp.Builder().retain(1).delete(3).retain(1).build(); // abcde -> ae
        TextOp b = new TextOp.Builder().retain(2).delete(3).build();           // abcde -> ab
        TextOp.Pair p = TextOp.transform(a, b);
        assertEquals("a", p.second().apply("ae"));
        assertEquals("a", p.first().apply("ab"));
    }

    @Test
    void transformRejectsMismatchWithMessage() {
        TextOp a = new TextOp.Builder().retain(2).build();
        TextOp b = new TextOp.Builder().retain(3).build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TextOp.transform(a, b));
        assertTrue(e.getMessage().startsWith("transform base mismatch"), e.getMessage());
    }

    @Test
    void invertLiteral() {
        TextOp op = new TextOp.Builder().retain(1).delete(2).insert("XY").retain(1).build();
        TextOp inv = op.invert("abcd");
        // canonical order puts the insert before the adjacent delete
        assertEquals("[R1, I\"bc\", D2, R1]", inv.toString());
        assertEquals("abcd", inv.apply("aXYd"));
    }

    @Test
    void invertRejectsWrongDoc() {
        TextOp op = new TextOp.Builder().retain(3).build();
        assertThrows(IllegalArgumentException.class, () -> op.invert("ab"));
    }

    @Test
    void randomApplyLengthsMatch() {
        Random r = new Random(1);
        for (int t = 0; t < 500; t++) {
            String d = randomDoc(r);
            TextOp op = randomOp(d, r);
            assertEquals(d.length(), op.baseLength());
            assertEquals(op.targetLength(), op.apply(d).length());
        }
    }

    @Test
    void randomComposeMatchesSequentialApply() {
        Random r = new Random(2);
        for (int t = 0; t < 1000; t++) {
            String d = randomDoc(r);
            TextOp a = randomOp(d, r);
            String d1 = a.apply(d);
            TextOp b = randomOp(d1, r);
            TextOp c = TextOp.compose(a, b);
            assertEquals(b.apply(d1), c.apply(d), () -> d + " " + a + " " + b + " => " + c);
        }
    }

    @Test
    void randomTransformConverges() {
        Random r = new Random(3);
        for (int t = 0; t < 2000; t++) {
            String d = randomDoc(r);
            TextOp a = randomOp(d, r);
            TextOp b = randomOp(d, r);
            TextOp.Pair p = TextOp.transform(a, b);
            String left = p.second().apply(a.apply(d));
            String right = p.first().apply(b.apply(d));
            assertEquals(left, right, () -> d + " a=" + a + " b=" + b);
            assertEquals(a.apply(d).length() + p.second().targetLength() - p.second().baseLength(), left.length());
        }
    }

    @Test
    void randomInvertRoundTrips() {
        Random r = new Random(4);
        for (int t = 0; t < 1000; t++) {
            String d = randomDoc(r);
            TextOp a = randomOp(d, r);
            assertEquals(d, a.invert(d).apply(a.apply(d)));
        }
    }

    @Test
    void canonicalFormLosesInsertSideOfDeletedRun() {
        // "delete 2 then insert x" is stored as "insert x then delete 2", so against a concurrent
        // insert at the start of the deleted run, x comes first (tie rule: first argument first).
        TextOp a = new TextOp.Builder().delete(2).insert("x").retain(2).build(); // abcd -> xcd
        TextOp b = new TextOp.Builder().insert("po").retain(4).build();
        TextOp.Pair p = TextOp.transform(a, b);
        assertEquals("xpocd", p.second().apply(a.apply("abcd")));
        assertEquals("xpocd", p.first().apply(b.apply("abcd")));
    }
}
