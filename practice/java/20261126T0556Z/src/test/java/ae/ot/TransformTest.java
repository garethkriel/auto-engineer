package ae.ot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TransformTest {

    private static TextOp insertAt(int len, int pos, String s) {
        return TextOp.builder().retain(pos).insert(s).retain(len - pos).build();
    }

    private static TextOp deleteRange(int len, int from, int to) {
        return TextOp.builder().retain(from).delete(to - from).retain(len - to).build();
    }

    private static String both(String doc, TextOp a, TextOp b) {
        TextOp.Pair p = TextOp.transform(a, b);
        String viaA = p.right().apply(a.apply(doc));
        String viaB = p.left().apply(b.apply(doc));
        assertEquals(viaA, viaB, "TP1 on '" + doc + "' a=" + a + " b=" + b);
        return viaA;
    }

    @Test
    void insertTieGoesToFirstArgument() {
        TextOp a = insertAt(3, 1, "X");
        TextOp b = insertAt(3, 1, "Y");
        assertEquals("aXYbc", both("abc", a, b));
        assertEquals("aYXbc", both("abc", b, a));
    }

    @Test
    void insertsAtDifferentPlaces() {
        assertEquals("XabYc", both("abc", insertAt(3, 0, "X"), insertAt(3, 2, "Y")));
    }

    @Test
    void overlappingDeletesDeleteOnce() {
        // "abcdef": a deletes "bcd", b deletes "cde"
        assertEquals("af", both("abcdef", deleteRange(6, 1, 4), deleteRange(6, 2, 5)));
    }

    @Test
    void identicalDeletes() {
        assertEquals("af", both("abcdef", deleteRange(6, 1, 5), deleteRange(6, 1, 5)));
    }

    @Test
    void insertInsideDeletedRangeSurvives() {
        // a inserts in the middle of the range b deletes
        assertEquals("aXf", both("abcdef", insertAt(6, 3, "X"), deleteRange(6, 1, 5)));
        assertEquals("aXf", both("abcdef", deleteRange(6, 1, 5), insertAt(6, 3, "X")));
    }

    @Test
    void insertAtEdgeOfDeletedRange() {
        assertEquals("aXef", both("abcdef", insertAt(6, 1, "X"), deleteRange(6, 1, 4)));
        assertEquals("abXf", both("abcdef", insertAt(6, 5, "X"), deleteRange(6, 2, 5)));
    }

    @Test
    void transformResultsHaveRightLengths() {
        TextOp a = insertAt(5, 2, "XYZ");
        TextOp b = deleteRange(5, 0, 2);
        TextOp.Pair p = TextOp.transform(a, b);
        assertEquals(b.targetLength(), p.left().baseLength());
        assertEquals(a.targetLength(), p.right().baseLength());
        assertEquals(p.left().targetLength(), p.right().targetLength());
    }

    @Test
    void transformRejectsDifferentBases() {
        assertThrows(IllegalArgumentException.class, () -> TextOp.transform(TextOp.identity(3), TextOp.identity(4)));
    }

    @Test
    void transformWithNoopIsNeutral() {
        TextOp a = TextOp.builder().retain(1).insert("Q").delete(2).retain(1).build();
        TextOp.Pair p = TextOp.transform(a, TextOp.identity(4));
        assertEquals(a, p.left());
        assertTrue(p.right().isNoop());
        TextOp.Pair q = TextOp.transform(TextOp.identity(4), a);
        assertTrue(q.left().isNoop());
        assertEquals(a, q.right());
    }

    // ---- randomized TP1 with provenance on unique characters ----

    /** Random op over a doc whose chars are unique; inserted chars come from the given pool (distinct). */
    private static TextOp uniqueOp(Random rng, int len, List<Character> pool) {
        TextOp.Builder b = TextOp.builder();
        int pos = 0;
        while (pos < len || rng.nextInt(4) == 0) {
            int r = rng.nextInt(10);
            if (r < 3 && !pool.isEmpty()) {
                StringBuilder s = new StringBuilder();
                int k = 1 + rng.nextInt(Math.min(3, pool.size()));
                for (int i = 0; i < k; i++) s.append(pool.remove(pool.size() - 1));
                b.insert(s.toString());
            } else if (pos < len) {
                int n = 1 + rng.nextInt(Math.min(3, len - pos));
                if (r < 6) b.delete(n); else b.retain(n);
                pos += n;
            } else {
                break;
            }
            if (pos >= len && rng.nextBoolean()) break;
        }
        b.retain(len - pos);
        return b.build();
    }

    private static String deletedChars(TextOp op, String doc) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        for (TextOp.Component c : op.components()) {
            if (c instanceof TextOp.Retain r) pos += r.n();
            else if (c instanceof TextOp.Delete d) { out.append(doc, pos, pos + d.n()); pos += d.n(); }
        }
        return out.toString();
    }

    private static List<String> insertedStrings(TextOp op) {
        List<String> out = new ArrayList<>();
        for (TextOp.Component c : op.components()) if (c instanceof TextOp.Insert i) out.add(i.text());
        return out;
    }

    @Test
    void tp1AndProvenanceOnRandomPairs() {
        Random rng = new Random(2024);
        int ties = 0;
        for (int iter = 0; iter < 5000; iter++) {
            int len = rng.nextInt(10);
            StringBuilder doc = new StringBuilder();
            for (int i = 0; i < len; i++) doc.append((char) ('a' + i));
            List<Character> poolA = new ArrayList<>();
            List<Character> poolB = new ArrayList<>();
            for (char c = 'A'; c <= 'H'; c++) poolA.add(c);
            for (char c = '0'; c <= '7'; c++) poolB.add(c);
            Collections.shuffle(poolA, rng);
            Collections.shuffle(poolB, rng);
            String d = doc.toString();
            TextOp a = uniqueOp(rng, len, poolA);
            TextOp b = uniqueOp(rng, len, poolB);
            String result = both(d, a, b);

            // survivors: original chars not deleted by either, in original order
            String gone = deletedChars(a, d) + deletedChars(b, d);
            StringBuilder survivors = new StringBuilder();
            for (char c : d.toCharArray()) if (gone.indexOf(c) < 0) survivors.append(c);
            StringBuilder seen = new StringBuilder();
            for (char c : result.toCharArray()) if (c >= 'a') seen.append(c);
            assertEquals(survivors.toString(), seen.toString(), d + " " + a + " " + b);

            // every inserted string is present, contiguous
            for (String s : insertedStrings(a)) assertTrue(result.contains(s), s + " lost in " + result);
            for (String s : insertedStrings(b)) assertTrue(result.contains(s), s + " lost in " + result);
            assertEquals(survivors.length() + insertedStrings(a).stream().mapToInt(String::length).sum()
                    + insertedStrings(b).stream().mapToInt(String::length).sum(), result.length());

            // swapping the arguments changes only tie order: same multiset of chars
            String swapped = both(d, b, a);
            char[] x = result.toCharArray();
            char[] y = swapped.toCharArray();
            java.util.Arrays.sort(x);
            java.util.Arrays.sort(y);
            assertArrayEquals(x, y);
            if (!result.equals(swapped)) ties++;
        }
        assertTrue(ties > 200, "random mix must contain many tie inserts, got " + ties);
    }

    @Test
    void tieOrderIsFirstArgumentFirstOnRandomTies() {
        Random rng = new Random(9);
        for (int i = 0; i < 500; i++) {
            int len = 1 + rng.nextInt(8);
            String doc = RandomOps.randomText(rng, len, "abcdefgh");
            int pos = rng.nextInt(len + 1);
            TextOp a = insertAt(len, pos, "AA");
            TextOp b = insertAt(len, pos, "BB");
            String r = both(doc, a, b);
            assertEquals(doc.substring(0, pos) + "AABB" + doc.substring(pos), r);
        }
    }
}
