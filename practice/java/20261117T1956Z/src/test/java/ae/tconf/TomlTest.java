package ae.tconf;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TomlTest {

    private static String p(String text) {
        return Toml.parse(text).toString();
    }

    private static TomlException err(String text) {
        return assertThrows(TomlException.class, () -> Toml.parse(text));
    }

    private static void at(String text, int line, int col) {
        TomlException e = err(text);
        assertEquals(line, e.line(), "line for " + text + " -> " + e.getMessage());
        assertEquals(col, e.column(), "column for " + text + " -> " + e.getMessage());
    }

    // ---------- scalars ----------

    @Test
    void emptyDocumentIsEmptyTable() {
        assertEquals("{}", p(""));
        assertEquals("{}", p("\n\n# only a comment\n   \n"));
    }

    @Test
    void basicScalars() {
        assertEquals("{a=1, b=\"x\", c=true, d=false, e=1.5}", p("a = 1\nb = \"x\"\nc = true\nd = false\ne = 1.5\n"));
    }

    @ParameterizedTest
    @CsvSource({
        "x = +5,5", "x = -5,-5", "x = 1_000,1000", "x = 0xFF,255", "x = 0xdead_beef,3735928559", "x = 0,0",
        "x = 9223372036854775807,9223372036854775807", "x = -9223372036854775808,-9223372036854775808"
    })
    void integers(String doc, String expected) {
        assertEquals("{x=" + expected + "}", p(doc));
    }

    @ParameterizedTest
    @CsvSource({"x = 1.5,1.5", "x = -2.5e-3,-0.0025", "x = 1e3,1000.0", "x = 1_0.0_1,10.01", "x = +0.5,0.5"})
    void floats(String doc, String expected) {
        assertEquals("{x=" + expected + "}", p(doc));
    }

    @Test
    void integerAndFloatAreDistinctTypes() {
        Table t = Toml.parse("i = 1\nf = 1.0\n");
        assertInstanceOf(Value.Int.class, t.get("i").orElseThrow());
        assertInstanceOf(Value.Flt.class, t.get("f").orElseThrow());
        assertEquals(1L, ((Value.Int) t.get("i").orElseThrow()).value());
    }

    @Test
    void basicStringEscapes() {
        Table t = Toml.parse("s = \"a\\tb\\n\\\"q\\\" \\\\ \\u00e9 \\u0041\"\n");
        assertEquals("a\tb\n\"q\" \\ \u00e9 A", ((Value.Str) t.get("s").orElseThrow()).value());
    }

    @Test
    void literalStringsKeepBackslashes() {
        Table t = Toml.parse("s = 'C:\\path\\n \"q\"'\n");
        assertEquals("C:\\path\\n \"q\"", ((Value.Str) t.get("s").orElseThrow()).value());
    }

    @Test
    void toStringEscapesStrings() {
        assertEquals("{s=\"a\\nb\\\"c\\\\\"}", p("s = \"a\\nb\\\"c\\\\\"\n"));
    }

    @Test
    void commentsAndCrlf() {
        assertEquals("{a=1, b=2}", p("# top\r\na = 1 # trailing\r\n\r\nb = 2\r\n"));
    }

    @Test
    void hashInsideStringIsNotAComment() {
        assertEquals("{a=\"x # y\"}", p("a = \"x # y\" # real\n"));
    }

    @Test
    void lastLineWithoutNewline() {
        assertEquals("{a=1}", p("a = 1"));
    }

    // ---------- keys ----------

    @Test
    void quotedAndDottedKeys() {
        assertEquals("{a={b={\"c d\"=1}}, \"x.y\"=2, 'lit'=3}".replace("'lit'", "lit"),
                p("a . b . \"c d\" = 1\n\"x.y\" = 2\n'lit' = 3\n"));
    }

    @Test
    void emptyQuotedKeyIsAllowed() {
        assertEquals("{\"\"=1}", p("\"\" = 1\n"));
    }

    @Test
    void dottedKeysShareParents() {
        assertEquals("{a={b=1, c=2, d={e=3}}}", p("a.b = 1\na.c = 2\na.d.e = 3\n"));
    }

    @Test
    void keysKeepInsertionOrder() {
        Table t = Toml.parse("z = 1\na = 2\nm = 3\n");
        assertEquals(List.of("z", "a", "m"), t.keys());
        assertEquals(3, t.size());
    }

    // ---------- arrays and inline tables ----------

    @Test
    void arrays() {
        assertEquals("{a=[1, 2, 3], b=[], c=[\"x\", 1, [true, 2.5]]}",
                p("a = [1, 2, 3,]\nb = [ ]\nc = [\"x\", 1, [true, 2.5]]\n"));
    }

    @Test
    void multilineArrayWithCommentsAndTrailingComma() {
        assertEquals("{a=[1, 2, 3]}", p("a = [ # start\n  1, # one\n  2,\n\n  3,\n  # end\n]\nb = 1".replace("b = 1", "")));
    }

    @Test
    void inlineTables() {
        assertEquals("{p={x=1, y={z=\"a\"}}, e={}}", p("p = { x = 1, y = {z = \"a\"} }\ne = {}\n"));
    }

    @Test
    void inlineTableWithDottedKeys() {
        assertEquals("{p={a={b=1, c=2}}}", p("p = {a.b = 1, a.c = 2}\n"));
    }

    @Test
    void arrayOfInlineTables() {
        assertEquals("{a=[{x=1}, {x=2}]}", p("a = [{x=1}, {x=2}]\n"));
    }

    // ---------- tables ----------

    @Test
    void tablesAndNestedTables() {
        String doc = "title = \"t\"\n[server]\nhost = \"h\"\nport = 80\n[server.tls]\non = true\n[ other . deep ]\nk = 1\n";
        assertEquals("{title=\"t\", server={host=\"h\", port=80, tls={on=true}}, other={deep={k=1}}}", p(doc));
    }

    @Test
    void implicitTableCanBeDefinedLater() {
        assertEquals("{a={b={x=1}, y=2}}", p("[a.b]\nx = 1\n[a]\ny = 2\n"));
    }

    @Test
    void arrayOfTables() {
        String doc = "[[p]]\nn = 1\n[[p]]\nn = 2\n[p.sub]\nk = 3\n[[p]]\nn = 4\n";
        assertEquals("{p=[{n=1}, {n=2, sub={k=3}}, {n=4}]}", p(doc));
    }

    @Test
    void nestedArrayOfTablesInsideArrayOfTables() {
        String doc = "[[a]]\nx = 1\n[[a.b]]\ny = 1\n[[a.b]]\ny = 2\n[[a]]\nx = 2\n[[a.b]]\ny = 3\n";
        assertEquals("{a=[{x=1, b=[{y=1}, {y=2}]}, {x=2, b=[{y=3}]}]}", p(doc));
    }

    @Test
    void getFollowsPathsThroughTables() {
        Table t = Toml.parse("[a.b]\nc = 7\n");
        assertEquals("7", t.get("a", "b", "c").orElseThrow().toString());
        assertTrue(t.get("a", "x").isEmpty());
        assertTrue(t.get("a", "b", "c", "d").isEmpty());
        assertTrue(t.get().isPresent());
    }

    // ---------- semantic errors ----------

    @Test
    void duplicateKey() {
        at("a = 1\na = 2\n", 2, 1);
        at("a = 1\n  a = 2\n", 2, 3);
        at("t = {a = 1, a = 2}\n", 1, 13);
        at("a.b = 1\na.b = 2\n", 2, 3);
    }

    @Test
    void duplicateTable() {
        at("[a]\nx = 1\n[a]\n", 3, 2);
        at("[a.b]\n[a.b]\n", 2, 4);
    }

    @Test
    void headerOverDottedKeysIsRejected() {
        at("[x]\na.b = 1\n[x.a]\n", 3, 4);
    }

    @Test
    void inlineTablesCannotBeExtended() {
        at("a = {x = 1}\n[a.y]\n", 2, 2);
        at("a = {x = 1}\na.z = 2\n", 2, 1);
        at("a = {x = 1}\n[a]\n", 2, 2);
    }

    @Test
    void scalarCannotBeUsedAsTable() {
        at("a = 1\n[a.b]\n", 2, 2);
        at("a = 1\na.b = 2\n", 2, 1);
    }

    @Test
    void arrayOfTablesConflicts() {
        at("a = [1]\n[[a]]\n", 2, 3);
        at("[a]\n[[a]]\n", 2, 3);
        at("[[a]]\n[a]\n", 2, 2);
        at("a = 1\n[[a]]\n", 2, 3);
    }

    // ---------- syntax errors ----------

    @Test
    void unterminatedStringPointsAtOpeningQuote() {
        at("a = \"abc\n", 1, 5);
        at("x = 1\nb = 'abc", 2, 5);
        at("a = \"abc", 1, 5);
    }

    @Test
    void invalidEscapePointsAtBackslash() {
        at("a = \"ab\\qc\"\n", 1, 8);
        at("a = \"\\u12G4\"\n", 1, 6);
        at("a = \"\\ud800\"\n", 1, 6);
    }

    @Test
    void unterminatedContainersPointAtOpeningBracket() {
        at("x = 1\na = [1, 2\n", 2, 5);
        at("a = [1,\n 2,\n", 1, 5);
        at("a = {x = 1\n", 1, 5);
        at("a = {x = 1", 1, 5);
    }

    @Test
    void inlineTableErrors() {
        at("a = {x = 1,}\n", 1, 12);
        at("a = {x = 1 y = 2}\n", 1, 12);
        at("a = {x = 1,\n y = 2}\n", 1, 5);
    }

    @Test
    void structuralSyntaxErrors() {
        at("a 1\n", 1, 3);
        at("a =\n", 1, 4);
        at("a = # c\n", 1, 5);
        at("= 1\n", 1, 1);
        at("a = 1 b = 2\n", 1, 7);
        at("a = \"x\" \"y\"\n", 1, 9);
        at("[a\nb = 1\n", 1, 3);
        at("[[a]\n", 1, 5);
        at("[]\n", 1, 2);
        at("a = [1 2]\n", 1, 8);
        at("a = [1,,2]\n", 1, 8);
        at("a = 1\r b = 2\n", 1, 6);
    }

    @ParameterizedTest
    @CsvSource({
        "a = 01,5", "a = 1__0,5", "a = _1,5", "a = 1_,5", "a = 0x,5", "a = 0b11,5", "a = 1.,5", "a = .5,5",
        "a = 1e,5", "a = tru,5", "a = True,5", "a = inf,5", "a = nan,5", "a = 1979-05-27,5", "a = --1,5"
    })
    void badScalars(String doc, int col) {
        TomlException e = err(doc + "\n");
        assertEquals(1, e.line());
        assertEquals(col, e.column());
    }

    @Test
    void outOfRangeNumbers() {
        at("a = 9223372036854775808\n", 1, 5);
        at("a = 0xFFFFFFFFFFFFFFFF\n", 1, 5);
        at("a = 1e999\n", 1, 5);
    }

    @Test
    void multilineStringsAreUnsupportedButClean() {
        err("a = \"\"\"x\"\"\"\n");
    }

    @Test
    void errorMessageCarriesPosition() {
        TomlException e = err("a = 1\nb = ?\n");
        assertTrue(e.getMessage().startsWith("line 2, column 5: "), e.getMessage());
    }

    @Test
    void errorLineCountsCrlfOnce() {
        at("a = 1\r\nb = 2\r\nb = 3\r\n", 3, 1);
    }

    // ---------- robustness ----------

    @Test
    void fuzzOnlyTomlExceptionEscapes() {
        String[] frags = {"a", "b", " ", "=", "1", "\"", "'", "[", "]", "[[", "]]", "{", "}", ",", ".", "\n", "\r\n", "#",
            "\\", "true", "0x", "-", "e", "_", "x.y", "\t", "\u00e9"};
        Random rnd = new Random(42);
        int parsed = 0;
        for (int i = 0; i < 20000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = rnd.nextInt(14);
            for (int j = 0; j < n; j++) {
                sb.append(frags[rnd.nextInt(frags.length)]);
            }
            try {
                Toml.parse(sb.toString());
                parsed++;
            } catch (TomlException e) {
                assertTrue(e.line() >= 1 && e.column() >= 1, sb.toString());
            }
        }
        assertTrue(parsed > 0, "fuzz should sometimes produce valid documents");
    }

    // ---------- model round trip ----------

    private static String key(Random r) {
        String[] pool = {"a", "b", "c", "key", "with space", "dot.ted", "x-y", "_z", "7"};
        return pool[r.nextInt(pool.length)];
    }

    private static String quoteKey(String k) {
        return "\"" + k + "\"";
    }

    private static String lit(Object o) {
        if (o instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(lit(l.get(i)));
            }
            return sb.append("]").toString();
        }
        return String.valueOf(o);
    }

    private static Object scalar(Random r) {
        return switch (r.nextInt(4)) {
            case 0 -> (long) r.nextInt(2000) - 1000;
            case 1 -> r.nextBoolean();
            case 2 -> "s" + r.nextInt(50) + (r.nextBoolean() ? "\n\"\\" : " # not comment");
            default -> {
                List<Object> l = new ArrayList<>();
                for (int i = r.nextInt(4); i > 0; i--) {
                    l.add((long) r.nextInt(9));
                }
                yield l;
            }
        };
    }

    private static Map<String, Object> model(Random r, int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = r.nextInt(5); i > 0; i--) {
            String k = key(r);
            if (m.containsKey(k)) {
                continue;
            }
            m.put(k, depth < 3 && r.nextInt(3) == 0 ? model(r, depth + 1) : scalar(r));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void emit(StringBuilder out, List<String> path, Map<String, Object> m) {
        if (!path.isEmpty()) {
            out.append('[');
            for (int i = 0; i < path.size(); i++) {
                out.append(i == 0 ? "" : ".").append(quoteKey(path.get(i)));
            }
            out.append("]\n");
        }
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                out.append(quoteKey(e.getKey())).append(" = ").append(lit(e.getValue())).append('\n');
            }
        }
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getValue() instanceof Map) {
                List<String> sub = new ArrayList<>(path);
                sub.add(e.getKey());
                emit(out, sub, (Map<String, Object>) e.getValue());
            }
        }
    }

    private static Object plain(Value v) {
        if (v instanceof Table t) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : t.keys()) {
                m.put(k, plain(t.get(k).orElseThrow()));
            }
            return m;
        }
        if (v instanceof Value.Arr a) {
            List<Object> l = new ArrayList<>();
            a.items().forEach(x -> l.add(plain(x)));
            return l;
        }
        if (v instanceof Value.Int i) {
            return i.value();
        }
        if (v instanceof Value.Bool b) {
            return b.value();
        }
        return ((Value.Str) v).value();
    }

    @Test
    void randomModelsRoundTripThroughHeaders() {
        Random r = new Random(7);
        for (int i = 0; i < 300; i++) {
            Map<String, Object> m = model(r, 0);
            StringBuilder doc = new StringBuilder();
            emit(doc, List.of(), m);
            // Scalars of a table are emitted before its sub-tables, so compare as sets of entries per table.
            Object got = plain(Toml.parse(doc.toString()));
            assertEquals(normalise(m), normalise(got), doc.toString());
        }
    }

    /** Order-insensitive view: scalars first then tables, matching emit order, recursively. */
    @SuppressWarnings("unchecked")
    private static Object normalise(Object o) {
        if (o instanceof Map<?, ?> raw) {
            Map<String, Object> m = (Map<String, Object>) raw;
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (!(v instanceof Map)) {
                    out.put(k, v);
                }
            });
            m.forEach((k, v) -> {
                if (v instanceof Map) {
                    out.put(k, normalise(v));
                }
            });
            return out;
        }
        return o;
    }

    @Test
    void largeDocumentParsesQuickly() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append("k").append(i).append(" = \"v").append(i).append("\" # c\n");
        }
        sb.append("[t]\n");
        for (int i = 0; i < 20000; i++) {
            sb.append("n").append(i).append(" = [").append(i).append(", ").append(i).append("]\n");
        }
        Table[] out = new Table[1];
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> out[0] = Toml.parse(sb.toString()));
        assertEquals(20001, out[0].size());
        assertEquals("[19999, 19999]", out[0].get("t", "n19999").orElseThrow().toString());
    }

    @Test
    void deepNestingDoesNotOverflowTheStack() {
        int depth = 200;
        String doc = "a = " + "[".repeat(depth) + "]".repeat(depth) + "\n";
        assertEquals("{a=" + "[".repeat(depth) + "]".repeat(depth) + "}", p(doc));
        err("a = " + "[".repeat(100000) + "\n");
    }
}
