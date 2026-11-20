package ae.roaring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class BitmapTableTest {

    //  row: city      color   age
    //   0   paris     red     30
    //   1   rome      blue    25
    //   2   paris     blue    41
    //   3   berlin    red     25
    //   4   new york  green   60
    //   5   rome      red     18
    static BitmapTable fixture() {
        BitmapTable.Builder b = BitmapTable.builder().category("city").category("color").number("age");
        b.row(Map.of("city", "paris", "color", "red", "age", "30"));
        b.row(Map.of("city", "rome", "color", "blue", "age", "25"));
        b.row(Map.of("city", "paris", "color", "blue", "age", "41"));
        b.row(Map.of("city", "berlin", "color", "red", "age", "25"));
        b.row(Map.of("city", "new york", "color", "green", "age", "60"));
        b.row(Map.of("city", "rome", "color", "red", "age", "18"));
        return b.build();
    }

    static void assertRows(BitmapTable t, String q, int... expected) {
        RoaringBitmap r = t.eval(q);
        r.checkInvariants();
        assertArrayEquals(expected, r.toArray(), q);
        assertEquals(expected.length, t.count(q), q);
    }

    static void assertError(BitmapTable t, String q, int column, String messagePart) {
        QueryException e = assertThrows(QueryException.class, () -> t.eval(q), q);
        assertEquals(column, e.column(), "column for [" + q + "]: " + e.getMessage());
        assertTrue(e.getMessage().contains(messagePart), "message for [" + q + "]: " + e.getMessage());
    }

    @Test
    void atoms() {
        BitmapTable t = fixture();
        assertEquals(6, t.rowCount());
        assertRows(t, "city = paris", 0, 2);
        assertRows(t, "city = 'new york'", 4);
        assertRows(t, "city != rome", 0, 2, 3, 4);
        assertRows(t, "city = atlantis");
        assertRows(t, "city != atlantis", 0, 1, 2, 3, 4, 5);
        assertRows(t, "color IN (red, green)", 0, 3, 4, 5);
        assertRows(t, "color in ('blue')", 1, 2);
        assertRows(t, "color IN (nope, blue)", 1, 2);
        assertRows(t, "age IN (25, 18)", 1, 3, 5);
        assertRows(t, "age < 25", 5);
        assertRows(t, "age <= 25", 1, 3, 5);
        assertRows(t, "age > 41", 4);
        assertRows(t, "age >= 41", 2, 4);
        assertRows(t, "age > -5", 0, 1, 2, 3, 4, 5);
        assertRows(t, "age = 25", 1, 3);
        assertRows(t, "age != 25", 0, 2, 4, 5);
        assertRows(t, "age BETWEEN 25 AND 41", 0, 1, 2, 3);
        assertRows(t, "age between 41 and 25");
    }

    @Test
    void precedenceNotBindsTighterThanAndThanOr() {
        BitmapTable t = fixture();
        // a OR b AND c == a OR (b AND c)
        assertRows(t, "city = berlin OR color = blue AND age > 30", 2, 3);
        assertRows(t, "(city = berlin OR color = blue) AND age > 30", 2);
        // NOT x AND y == (NOT x) AND y
        assertRows(t, "NOT color = red AND age < 50", 1, 2);
        assertRows(t, "NOT (color = red AND age < 50)", 1, 2, 4);
        assertRows(t, "NOT NOT city = rome", 1, 5);
        assertRows(t, "NOT city = rome OR color = green AND age > 50", 0, 2, 3, 4);
    }

    @Test
    void betweenInsideAndChainAndCaseInsensitiveKeywordsAndQuotedKeywords() {
        BitmapTable t = fixture();
        assertRows(t, "age BETWEEN 20 AND 30 AND color = red", 0, 3);
        assertRows(t, "city = paris and not color = blue", 0);
        assertRows(t, "city = 'new york' OR city = 'AND'", 4);
        assertRows(t, "  city\t=\n paris  ", 0, 2);
    }

    @Test
    void syntaxAndSemanticErrorsReportTheColumnOfTheOffendingToken() {
        BitmapTable t = fixture();
        assertError(t, "", 1, "expected");
        assertError(t, "city = ", 8, "value");
        assertError(t, "city paris", 6, "operator");
        assertError(t, "bogus = 1", 1, "unknown column");
        assertError(t, "City = paris", 1, "unknown column");       // column names are case-sensitive
        assertError(t, "age = abc", 7, "integer");
        assertError(t, "age = 99999999999", 7, "integer");
        assertError(t, "(city = paris", 14, "')'");
        assertError(t, "city = paris )", 14, "end of query");
        assertError(t, "city = paris city = rome", 14, "end of query");
        assertError(t, "city < 5", 1, "numeric");
        assertError(t, "city = 'paris", 8, "unterminated");
        assertError(t, "city = paris AND", 17, "expected");
        assertError(t, "city = and", 8, "quote");
        assertError(t, "age BETWEEN 3 4", 15, "AND");
        assertError(t, "color IN red", 10, "'('");
        assertError(t, "color IN (red", 14, "')'");
        assertError(t, "city = paris @", 14, "unexpected character");
        // Control: the same shapes without the defect evaluate.
        assertRows(t, "city = paris", 0, 2);
        assertRows(t, "color IN (red)", 0, 3, 5);
        assertRows(t, "age BETWEEN 3 AND 4");
    }

    @Test
    void countAndSum() {
        BitmapTable t = fixture();
        assertEquals(73, t.sum("age", "color = red"));
        assertEquals(0, t.sum("age", "city = atlantis"));
        assertEquals(30 + 25 + 41 + 25 + 60 + 18, t.sum("age", "age >= 0"));
        assertEquals(3, t.count("color = red"));
        assertThrows(IllegalArgumentException.class, () -> t.sum("city", "age > 0"));
        assertThrows(IllegalArgumentException.class, () -> t.sum("nope", "age > 0"));
    }

    @Test
    void builderRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> BitmapTable.builder().category("a").number("a"));
        BitmapTable.Builder b = BitmapTable.builder().category("c").number("n");
        assertThrows(IllegalArgumentException.class, () -> b.row(Map.of("c", "x")));                       // missing n
        assertThrows(IllegalArgumentException.class, () -> b.row(Map.of("c", "x", "n", "1", "z", "2")));    // extra z
        assertThrows(IllegalArgumentException.class, () -> b.row(Map.of("c", "x", "n", "one")));
        assertThrows(IllegalArgumentException.class, () -> b.row(Map.of("c", "x", "n", "-4")));
        b.row(Map.of("c", "x", "n", "4"));                                                                 // control row loads
        assertEquals(1, b.build().rowCount());
    }

    // ------------- randomised differential against row-by-row evaluation -------------

    record Row(String city, String color, int age) {}

    interface Node {
        String text();
        boolean eval(Row r);
    }

    record Atom(String text, Predicate<Row> p) implements Node {
        public boolean eval(Row r) { return p.test(r); }
    }

    record Not(Node n) implements Node {
        public String text() { return "NOT (" + n.text() + ")"; }
        public boolean eval(Row r) { return !n.eval(r); }
    }

    record And(Node a, Node b) implements Node {
        public String text() { return "(" + a.text() + ") AND (" + b.text() + ")"; }
        public boolean eval(Row r) { return a.eval(r) && b.eval(r); }
    }

    record Or(Node a, Node b) implements Node {
        public String text() { return "(" + a.text() + ") OR (" + b.text() + ")"; }
        public boolean eval(Row r) { return a.eval(r) || b.eval(r); }
    }

    static final String[] CITIES = {"paris", "rome", "berlin", "new york", "oslo"};
    static final String[] COLORS = {"red", "blue", "green"};

    static String q(String v) { return "'" + v + "'"; }

    static Node atom(Random r) {
        switch (r.nextInt(7)) {
            case 0: { String v = CITIES[r.nextInt(5)]; return new Atom("city = " + q(v), x -> x.city().equals(v)); }
            case 1: { String v = COLORS[r.nextInt(3)]; return new Atom("color != " + q(v), x -> !x.color().equals(v)); }
            case 2: {
                String a = CITIES[r.nextInt(5)], b = CITIES[r.nextInt(5)];
                return new Atom("city IN (" + q(a) + ", " + q(b) + ")", x -> x.city().equals(a) || x.city().equals(b));
            }
            case 3: { int v = r.nextInt(70); return new Atom("age < " + v, x -> x.age() < v); }
            case 4: { int v = r.nextInt(70); return new Atom("age >= " + v, x -> x.age() >= v); }
            case 5: { int lo = r.nextInt(60), hi = lo + r.nextInt(20); return new Atom("age BETWEEN " + lo + " AND " + hi, x -> x.age() >= lo && x.age() <= hi); }
            default: { int v = r.nextInt(70); return new Atom("age = " + v, x -> x.age() == v); }
        }
    }

    static Node gen(Random r, int depth) {
        if (depth == 0 || r.nextInt(4) == 0) return atom(r);
        switch (r.nextInt(3)) {
            case 0: return new Not(gen(r, depth - 1));
            case 1: return new And(gen(r, depth - 1), gen(r, depth - 1));
            default: return new Or(gen(r, depth - 1), gen(r, depth - 1));
        }
    }

    static List<Row> rows(Random r, int n) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(new Row(CITIES[r.nextInt(5)], COLORS[r.nextInt(3)], r.nextInt(70)));
        return rows;
    }

    static BitmapTable tableOf(List<Row> rows) {
        BitmapTable.Builder b = BitmapTable.builder().category("city").category("color").number("age");
        for (Row x : rows) b.row(Map.of("city", x.city(), "color", x.color(), "age", Integer.toString(x.age())));
        return b.build();
    }

    @Test
    void generatedParenthesisedQueriesMatchRowByRowEvaluation() {
        Random r = new Random(2026);
        List<Row> rows = rows(r, 400);
        BitmapTable t = tableOf(rows);
        int nonTrivial = 0;
        for (int i = 0; i < 500; i++) {
            Node n = gen(r, 4);
            int[] expected = java.util.stream.IntStream.range(0, rows.size()).filter(k -> n.eval(rows.get(k))).toArray();
            assertArrayEquals(expected, t.eval(n.text()).toArray(), n.text());
            if (expected.length > 0 && expected.length < rows.size()) nonTrivial++;
        }
        assertTrue(nonTrivial > 250, "generator must produce mixed outcomes, got " + nonTrivial);
    }

    @Test
    void generatedFlatChainsUsePrecedenceNotParentheses() {
        Random r = new Random(99);
        List<Row> rows = rows(r, 300);
        BitmapTable t = tableOf(rows);
        int nonTrivial = 0;
        for (int i = 0; i < 300; i++) {
            // OR-groups of AND-chains of (optionally negated) atoms, printed without any parentheses.
            int groups = 1 + r.nextInt(3);
            List<List<Node>> g = new ArrayList<>();
            List<String> orParts = new ArrayList<>();
            for (int a = 0; a < groups; a++) {
                int len = 1 + r.nextInt(3);
                List<Node> chain = new ArrayList<>();
                List<String> andParts = new ArrayList<>();
                for (int k = 0; k < len; k++) {
                    Node at = atom(r);
                    boolean neg = r.nextInt(3) == 0;
                    chain.add(neg ? new Not(at) : at);
                    andParts.add(neg ? "NOT " + at.text() : at.text());
                }
                g.add(chain);
                orParts.add(String.join(" AND ", andParts));
            }
            String text = String.join(" OR ", orParts);
            int[] expected = java.util.stream.IntStream.range(0, rows.size())
                    .filter(k -> g.stream().anyMatch(chain -> chain.stream().allMatch(n -> n.eval(rows.get(k)))))
                    .toArray();
            assertArrayEquals(expected, t.eval(text).toArray(), text);
            if (expected.length > 0 && expected.length < rows.size()) nonTrivial++;
        }
        assertTrue(nonTrivial > 100, "got " + nonTrivial);
    }

    @Test
    void largeTableQueriesAreFastAndCorrect() {
        Random r = new Random(5);
        List<Row> rows = rows(r, 200_000);
        BitmapTable t = tableOf(rows);
        long expected = rows.stream().filter(x -> x.color().equals("red") && x.age() >= 20 && x.age() <= 40).count();
        long start = System.nanoTime();
        long got = t.count("color = red AND age BETWEEN 20 AND 40");
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(expected, got);
        assertTrue(ms < 2000, "query took " + ms + " ms");
        long sum = rows.stream().filter(x -> x.city().equals("oslo")).mapToLong(Row::age).sum();
        assertEquals(sum, t.sum("age", "city = oslo"));
    }
}
