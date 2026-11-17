package tpl;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TemplateTest {

    private static String render(String src, Map<String, ?> data) {
        return Template.parse(src).render(data);
    }

    private static String render(String src) {
        return render(src, Map.of());
    }

    // ---- output ----

    @Test
    void plainTextIsUnchanged() {
        assertEquals("just text\nwith lines", render("just text\nwith lines"));
    }

    @Test
    void substitutesVariable() {
        assertEquals("Hello, World!", render("Hello, {{ name }}!", Map.of("name", "World")));
    }

    @Test
    void resolvesDottedPathsThroughMapsAndListIndexes() {
        Map<String, Object> data = Map.of(
                "user", Map.of("address", Map.of("city", "Oslo")),
                "items", List.of("a", "b", "c"));
        assertEquals("Oslo b", render("{{ user.address.city }} {{ items.1 }}", data));
    }

    @Test
    void literalsAreOutputDirectly() {
        assertEquals("hi 42 it's", render("{{ \"hi\" }} {{ 42 }} {{ 'it\\'s' | raw }}"));
    }

    @Test
    void autoEscapesHtmlAndRawDisablesIt() {
        Map<String, Object> data = Map.of("s", "<a href=\"x\">&'");
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", render("{{ s }}", data));
        assertEquals("<a href=\"x\">&'", render("{{ s | raw }}", data));
    }

    @Test
    void escapingAppliesToFilterOutput() {
        assertEquals("&lt;B&gt;", render("{{ s | upper }}", Map.of("s", "<b>")));
    }

    @Test
    void filtersChainLeftToRight() {
        assertEquals("AB", render("{{ s | trim | upper }}", Map.of("s", "  ab ")));
        assertEquals("ab", render("{{ s | upper | lower }}", Map.of("s", "Ab")));
    }

    @Test
    void lengthOfStringCollectionAndMap() {
        Map<String, Object> data = Map.of("s", "four", "l", List.of(1, 2, 3), "m", Map.of("k", 1));
        assertEquals("4 3 1", render("{{ s | length }} {{ l | length }} {{ m | length }}", data));
    }

    @Test
    void defaultReplacesMissingNullAndEmpty() {
        Map<String, Object> data = new HashMap<>();
        data.put("empty", "");
        data.put("nul", null);
        data.put("full", "yes");
        assertEquals("n/a|x|y|yes",
                render("{{ missing | default \"n/a\" }}|{{ empty | default 'x' }}|{{ nul | default \"y\" }}|{{ full | default \"z\" }}", data));
    }

    // ---- conditions ----

    static Stream<Arguments> truthiness() {
        return Stream.of(
                Arguments.of("", false), Arguments.of("a", true),
                Arguments.of(0, false), Arguments.of(1, true), Arguments.of(0.0, false),
                Arguments.of(false, false), Arguments.of(true, true),
                Arguments.of(List.of(), false), Arguments.of(List.of(1), true),
                Arguments.of(Map.of(), false), Arguments.of(Map.of("k", 1), true));
    }

    @ParameterizedTest
    @MethodSource("truthiness")
    void truthinessTable(Object value, boolean expected) {
        assertEquals(expected ? "T" : "F", render("{% if v %}T{% else %}F{% endif %}", Map.of("v", value)));
    }

    @Test
    void nullAndMissingAreFalsy() {
        Map<String, Object> data = new HashMap<>();
        data.put("v", null);
        assertEquals("F", render("{% if v %}T{% else %}F{% endif %}", data));
        assertEquals("F", render("{% if nope %}T{% else %}F{% endif %}"));
    }

    @Test
    void andBindsTighterThanOrAndNotTighterThanComparison() {
        Map<String, Object> d = Map.of("a", true, "b", false, "c", false);
        assertEquals("T", render("{% if a or b and c %}T{% else %}F{% endif %}", d));
        assertEquals("F", render("{% if b or b and a %}T{% else %}F{% endif %}", d));
        assertEquals("T", render("{% if not x == \"x\" %}T{% else %}F{% endif %}", Map.of("x", "y")));
        assertEquals("F", render("{% if not not b %}T{% else %}F{% endif %}", d));
    }

    @Test
    void numbersCompareByValueAcrossTypes() {
        String t = "{% if n == 3 %}eq{% else %}ne{% endif %}";
        assertEquals("eq", render(t, Map.of("n", 3)));
        assertEquals("eq", render(t, Map.of("n", 3L)));
        assertEquals("eq", render(t, Map.of("n", 3.0)));
        assertEquals("eq", render(t, Map.of("n", new BigDecimal("3.00"))));
        assertEquals("ne", render(t, Map.of("n", 4)));
        assertEquals("ne", render(t, Map.of("n", "3")));
    }

    @Test
    void stringAndPathComparisons() {
        Map<String, Object> d = Map.of("a", "abc", "b", "abc", "c", "x");
        assertEquals("1", render("{% if a == \"abc\" %}1{% endif %}", d));
        assertEquals("2", render("{% if a == b %}2{% endif %}", d));
        assertEquals("3", render("{% if a != c %}3{% endif %}", d));
        assertEquals("", render("{% if a != b %}4{% endif %}", d));
        assertEquals("5", render("{% if missing != a %}5{% endif %}", d));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"95,A", "85,B", "70,C", "10,F"})
    void elifChain(int score, String grade) {
        String t = "{% if s == 95 %}A{% elif s == 85 %}B{% elif s == 70 %}C{% else %}F{% endif %}";
        assertEquals(grade, render(t, Map.of("s", score)));
    }

    // ---- loops ----

    @Test
    void forIteratesAndEmptyListRendersNothing() {
        String t = "{% for x in xs %}[{{ x }}]{% endfor %}";
        assertEquals("[1][2][3]", render(t, Map.of("xs", List.of(1, 2, 3))));
        assertEquals("", render(t, Map.of("xs", List.of())));
    }

    @Test
    void loopVariables() {
        String t = "{% for x in xs %}{{ loop.index }}/{{ loop.index0 }}/{{ loop.length }}"
                + "{% if loop.first %}F{% endif %}{% if loop.last %}L{% endif %}{{ x }},{% endfor %}";
        assertEquals("1/0/3Fa,2/1/3b,3/2/3Lc,", render(t, Map.of("xs", List.of("a", "b", "c"))));
    }

    @Test
    void nestedLoopsShadowLoopAndSeeOuterVariables() {
        String t = "{% for r in rows %}{{ r }}:{% for c in cols %}{{ loop.index }}{{ r }}{{ c }} {% endfor %}{{ loop.index }}|{% endfor %}";
        assertEquals("a:1aX 2aY 1|b:1bX 2bY 2|",
                render(t, Map.of("rows", List.of("a", "b"), "cols", List.of("X", "Y"))));
    }

    @Test
    void loopVariableIsGoneAfterLoop() {
        assertEquals("[1][2]gone",
                render("{% for x in xs %}[{{ x }}]{% endfor %}{{ x | default \"gone\" }}", Map.of("xs", List.of(1, 2))));
    }

    @Test
    void loopOverListOfMaps() {
        Map<String, Object> d = Map.of("people", List.of(Map.of("name", "Ann"), Map.of("name", "Bob")));
        assertEquals("Ann,Bob,", render("{% for p in people %}{{ p.name }},{% endfor %}", d));
    }

    // ---- comments and whitespace ----

    @Test
    void commentsAreDropped() {
        assertEquals("ab", render("a{# note {{ x }} #}b"));
    }

    @Test
    void whitespaceControlStripsOnlyMarkedSides() {
        assertEquals("a1b", render("a  \n  {{- x -}}  \n  b", Map.of("x", 1)));
        assertEquals("a 1 b", render("a {{ x }} b", Map.of("x", 1)));
        assertEquals("a  1", render("a  {{ x -}}  \n 1", Map.of("x", "")));
        assertEquals("T", render("  {%- if t -%}  T  {%- endif -%}  ", Map.of("t", true)));
    }

    // ---- syntax errors ----

    private static TemplateSyntaxException syntaxError(String src) {
        return assertThrows(TemplateSyntaxException.class, () -> Template.parse(src));
    }

    private static void assertAt(TemplateException e, int line, int col, String messagePart) {
        assertEquals(line, e.line(), e.getMessage());
        assertEquals(col, e.column(), e.getMessage());
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    @Test
    void unclosedOutputTag() {
        assertAt(syntaxError("Hello {{ name"), 1, 7, "unclosed");
    }

    @Test
    void unclosedBlockReportsOpeningTagPosition() {
        assertAt(syntaxError("line1\nline2 {% if x %}no end"), 2, 7, "unclosed 'if'");
        assertAt(syntaxError("{% for a in b %}\n{% if a %}x{% endif %}"), 1, 1, "unclosed 'for'");
    }

    @Test
    void strayEndTag() {
        assertAt(syntaxError("{% endif %}"), 1, 4, "unexpected 'endif'");
        assertAt(syntaxError("{% if a %}x{% endfor %}"), 1, 15, "unexpected 'endfor'");
        assertAt(syntaxError("{% else %}"), 1, 4, "unexpected 'else'");
    }

    @Test
    void unknownFilterAndTag() {
        assertAt(syntaxError("{{ a | nosuch }}"), 1, 8, "unknown filter 'nosuch'");
        assertAt(syntaxError("{% while x %}"), 1, 4, "unknown tag 'while'");
    }

    @Test
    void filterArgumentArity() {
        assertAt(syntaxError("{{ a | default }}"), 1, 8, "default");
        assertAt(syntaxError("{{ a | upper \"x\" }}"), 1, 14, "unexpected");
    }

    @Test
    void unterminatedStringPointsAtItsStart() {
        assertAt(syntaxError("{{ \"abc }}"), 1, 4, "unterminated string");
    }

    @Test
    void badForSyntax() {
        assertAt(syntaxError("{% for x of xs %}{% endfor %}"), 1, 10, "'in'");
    }

    @Test
    void unexpectedCharacter() {
        assertAt(syntaxError("{{ a # b }}"), 1, 6, "unexpected character '#'");
    }

    @Test
    void missingOperandIsReportedOnItsLine() {
        TemplateSyntaxException e = syntaxError("x\n{% if a == %}y{% endif %}");
        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains("operand"), e.getMessage());
    }

    @Test
    void trailingTokensAndEmptyTags() {
        assertTrue(syntaxError("{% if a b %}x{% endif %}").getMessage().contains("unexpected"));
        assertTrue(syntaxError("{% endif extra %}").getMessage().contains("unexpected"));
        assertTrue(syntaxError("{{ }}").getMessage().contains("operand"));
        assertTrue(syntaxError("{% %}").getMessage().contains("empty"));
        assertTrue(syntaxError("{{ a. }}").getMessage().contains("path"));
    }

    @Test
    void positionsCountLinesAcrossMultilineText() {
        assertAt(syntaxError("a\nb\n\n   {{ x | bad }}"), 4, 11, "unknown filter 'bad'");
    }

    // ---- render errors ----

    @Test
    void undefinedVariableReportsPosition() {
        Template t = Template.parse("ok\n  {{ nope }}");
        TemplateRenderException e = assertThrows(TemplateRenderException.class, () -> t.render(Map.of()));
        assertAt(e, 2, 6, "nope");
    }

    @Test
    void undefinedNestedSegmentIsAnError() {
        assertThrows(TemplateRenderException.class, () -> render("{{ a.b.c }}", Map.of("a", Map.of("b", Map.of()))));
        assertThrows(TemplateRenderException.class, () -> render("{{ a.5 }}", Map.of("a", List.of(1))));
    }

    @Test
    void forOverNonIterableAndMissing() {
        TemplateRenderException e = assertThrows(TemplateRenderException.class,
                () -> render("{% for x in n %}{% endfor %}", Map.of("n", 5)));
        assertTrue(e.getMessage().contains("not iterable"), e.getMessage());
        assertThrows(TemplateRenderException.class, () -> render("{% for x in n %}{% endfor %}"));
    }

    @Test
    void lengthOfNumberFails() {
        assertThrows(TemplateRenderException.class, () -> render("{{ n | length }}", Map.of("n", 5)));
    }

    // ---- API behaviour ----

    @Test
    void templateIsReusableAndDoesNotMutateData() {
        Template t = Template.parse("{% for x in xs %}{{ x }}{% endfor %}-{{ n }}");
        assertEquals("12-a", t.render(Map.of("xs", List.of(1, 2), "n", "a")));
        assertEquals("-b", t.render(Map.of("xs", List.of(), "n", "b")));
        assertEquals("12-a", t.render(Map.of("xs", List.of(1, 2), "n", "a")));
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> Template.parse(null));
        assertThrows(NullPointerException.class, () -> Template.parse("x").render(null));
    }

    // ---- robustness and speed ----

    @Test
    void fuzzOnlyTemplateExceptionsEscape() {
        String[] frags = {"{{", "}}", "{%", "%}", "{#", "#}", "-", "|", " ", "\n", "\"", "'", "\\", "a", "b.c", ".", "0",
                "if", "elif", "else", "endif", "for", "in", "endfor", "not", "and", "or", "==", "!=", "upper", "default", "x"};
        Random rnd = new Random(12345);
        Map<String, Object> data = Map.of("a", "A", "b", Map.of("c", List.of(1, 2)), "x", List.of("p", "q"));
        int parsed = 0;
        for (int i = 0; i < 20000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + rnd.nextInt(14);
            for (int j = 0; j < n; j++) {
                sb.append(frags[rnd.nextInt(frags.length)]);
            }
            try {
                Template t = Template.parse(sb.toString());
                parsed++;
                t.render(data);
            } catch (TemplateException expected) {
                // fine: errors are reported as TemplateException only
            }
        }
        assertTrue(parsed > 100, "fuzzer should produce some valid templates, got " + parsed);
    }

    @Test
    void generatedValidTemplatesRoundTripAgainstDirectComputation() {
        Random rnd = new Random(7);
        for (int i = 0; i < 300; i++) {
            int len = rnd.nextInt(8);
            List<Integer> xs = new ArrayList<>();
            StringBuilder expected = new StringBuilder();
            for (int k = 0; k < len; k++) {
                int v = rnd.nextInt(5);
                xs.add(v);
                expected.append(v % 2 == 0 ? "e" : "o").append(v).append(k == len - 1 ? "." : ",");
            }
            String t = "{% for v in xs %}{% if v == 0 or v == 2 or v == 4 %}e{% else %}o{% endif %}{{ v }}"
                    + "{% if loop.last %}.{% else %},{% endif %}{% endfor %}";
            assertEquals(expected.toString(), render(t, Map.of("xs", xs)));
        }
    }

    @Test
    void largeLoopRendersQuickly() {
        List<Integer> xs = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            xs.add(i);
        }
        Template t = Template.parse("{% for x in xs %}{% if x == 99999 %}last{% endif %}{{ x }}{% endfor %}");
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            String out = t.render(Map.of("xs", xs));
            assertTrue(out.startsWith("0123"));
            assertTrue(out.endsWith("99998last99999"));
        });
    }
}
