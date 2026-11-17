package tmpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TemplateTest {

    private static String render(String src, Map<String, ?> ctx) {
        return Template.parse(src).render(ctx);
    }

    private static String render(String src) {
        return render(src, Map.of());
    }

    private static TemplateSyntaxException syntax(String src) {
        return assertThrows(TemplateSyntaxException.class, () -> Template.parse(src));
    }

    private static void at(TemplateException e, int line, int col) {
        assertEquals(line, e.getLine(), e.getMessage());
        assertEquals(col, e.getColumn(), e.getMessage());
    }

    // ---- text, output, escaping -------------------------------------------------

    @Test
    void plainTextAndLoneBracesPassThrough() {
        assertEquals("", render(""));
        assertEquals("hello { a } {x} }}", render("hello { a } {x} }}"));
    }

    @Test
    void outputsVariablesAndNestedPaths() {
        Map<String, Object> user = Map.of("name", "Ada", "tags", List.of("x", "y", "z"));
        Map<String, Object> ctx = Map.of("user", user, "n", 42);
        assertEquals("Ada/y/42", render("{{ user.name }}/{{user.tags.1}}/{{ n }}", ctx));
    }

    @Test
    void missingValuesRenderEmpty() {
        Map<String, Object> ctx = Map.of("user", Map.of("tags", List.of("x")));
        assertEquals("[][][][]",
                render("[{{ nope }}][{{ user.nope.deeper }}][{{ user.tags.5 }}][{{ user.tags.x }}]", ctx));
    }

    @Test
    void htmlEscapesByDefaultAndRawSkipsIt() {
        Map<String, Object> ctx = Map.of("s", "<a href=\"x\">&'</a>");
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;", render("{{ s }}", ctx));
        assertEquals("<a href=\"x\">&'</a>", render("{{ s | raw }}", ctx));
    }

    @Test
    void numbersRenderWithoutTrailingPointZero() {
        assertEquals("3", render("{{ n }}", Map.of("n", 3)));
        assertEquals("3", render("{{ n }}", Map.of("n", 3.0)));
        assertEquals("2.5", render("{{ n }}", Map.of("n", 2.5)));
        assertEquals("-0.5", render("{{ n }}", Map.of("n", -0.5)));
        assertEquals("1.25", render("{{ n }}", Map.of("n", new BigDecimal("1.25"))));
        assertEquals("7", render("{{ n }}", Map.of("n", 7L)));
    }

    @Test
    void booleansAndLiteralsRender() {
        assertEquals("true|false|abc|7|", render("{{ true }}|{{ false }}|{{ 'abc' }}|{{ 7 }}|{{ null }}"));
    }

    @Test
    void stringLiteralEscapes() {
        assertEquals("a'b\"c\\d\ne", render("{{ 'a\\'b\"c\\\\d\\ne' | raw }}"));
    }

    // ---- filters ----------------------------------------------------------------

    @Test
    void filtersChainLeftToRight() {
        assertEquals("ADA", render("{{ s | trim | upper }}", Map.of("s", "  ada ")));
        assertEquals("ada", render("{{ s | upper | lower }}", Map.of("s", "AdA")));
    }

    @Test
    void lengthOfStringListMapAndNull() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("s", "héllo");
        ctx.put("l", List.of(1, 2, 3));
        ctx.put("m", Map.of("a", 1));
        ctx.put("z", null);
        assertEquals("5 3 1 0",
                render("{{ s | length }} {{ l | length }} {{ m | length }} {{ z | length }}", ctx));
    }

    @Test
    void defaultReplacesNullAndEmptyOnly() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("e", "");
        ctx.put("zero", 0);
        ctx.put("f", false);
        assertEquals("d|d|0|false", render(
                "{{ e | default('d') }}|{{ missing | default('d') }}|{{ zero | default('d') }}|{{ f | default('d') }}",
                ctx));
    }

    @Test
    void joinStringifiesElements() {
        Map<String, Object> ctx = Map.of("l", List.of("a", 2, 3.0, true));
        assertEquals("a, 2, 3, true", render("{{ l | join(', ') }}", ctx));
        assertEquals("", render("{{ missing | join(',') }}"));
    }

    @Test
    void joinOnNonIterableIsRenderErrorWithPosition() {
        TemplateRenderException e = assertThrows(TemplateRenderException.class,
                () -> render("ab\n{{ 5 | join(',') }}"));
        at(e, 2, 8);
    }

    @Test
    void lengthOfNumberIsRenderError() {
        assertThrows(TemplateRenderException.class, () -> render("{{ 5 | length }}"));
    }

    @Test
    void unknownFilterAndWrongArityAreSyntaxErrorsAtTheFilterName() {
        at(syntax("{{ name | shout }}"), 1, 11);
        at(syntax("{{ name | upper('x') }}"), 1, 11);
        at(syntax("{{ name | default }}"), 1, 11);
        at(syntax("{{ name | join(1, 2) }}"), 1, 11);
    }

    @Test
    void filterArgumentsMustBeLiterals() {
        at(syntax("{{ a | default(b) }}"), 1, 16);
    }

    // ---- conditions -------------------------------------------------------------

    @Test
    void truthinessOfScalars() {
        String t = "{% if v %}yes{% else %}no{% endif %}";
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("v", null);
        assertEquals("no", render(t, ctx));
        for (Object falsy : new Object[] {"", 0, 0L, 0.0, BigDecimal.ZERO, false}) {
            ctx.put("v", falsy);
            assertEquals("no", render(t, ctx), "falsy: " + falsy);
        }
        for (Object truthy : new Object[] {"x", " ", "0", 1, -1, 0.5, true, new BigDecimal("0.10")}) {
            ctx.put("v", truthy);
            assertEquals("yes", render(t, ctx), "truthy: " + truthy);
        }
    }

    @Test
    void emptyCollectionsAreFalsyAndNonEmptyTruthy() {
        Map<String, Object> ctx = Map.of("el", List.of(), "l", List.of(0), "em", Map.of(), "m", Map.of("k", 1));
        assertEquals("nonoyesyes", render(
                "{% if el %}yes{% else %}no{% endif %}"
                + "{% if em %}yes{% else %}no{% endif %}"
                + "{% if l %}yes{% else %}no{% endif %}"
                + "{% if m %}yes{% else %}no{% endif %}", ctx));
    }

    @Test
    void elifChainPicksFirstTrueBranchOnly() {
        String t = "{% if n == 1 %}one{% elif n == 2 %}two{% elif n >= 2 %}many{% else %}other{% endif %}";
        assertEquals("one", render(t, Map.of("n", 1)));
        assertEquals("two", render(t, Map.of("n", 2)));
        assertEquals("many", render(t, Map.of("n", 3)));
        assertEquals("other", render(t, Map.of("n", 0)));
        assertEquals("", render("{% if false %}x{% elif false %}y{% endif %}"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        "1 == 1;T", "1 == 1.0;T", "1 == 2;F", "1 != 2;T", "'a' == 'a';T", "'a' == 'A';F",
        "1 == '1';F", "null == null;T", "missing == null;T", "missing == 0;F",
        "1 < 2;T", "2 < 2;F", "2 <= 2;T", "3 > 2;T", "2 >= 3;F", "'abc' < 'abd';T", "'b' > 'abc';T",
        "true == true;T", "true == 1;F"
    })
    void comparisonTable(String cond, String expected) {
        assertEquals(expected, render("{% if " + cond + " %}T{% else %}F{% endif %}"));
    }

    @Test
    void numbersCompareByValueAcrossTypes() {
        Map<String, Object> ctx = Map.of("i", 3, "l", 3L, "d", 3.0, "bd", new BigDecimal("3.00"), "f", 0.1);
        assertEquals("TTTT", render("{% if i == l %}T{% endif %}{% if l == d %}T{% endif %}"
                + "{% if d == bd %}T{% endif %}{% if f == 0.1 %}T{% endif %}", ctx));
    }

    @Test
    void orderingNumberWithStringIsRenderErrorAtTheOperator() {
        TemplateRenderException e = assertThrows(TemplateRenderException.class,
                () -> render("{% if 1 < 'a' %}x{% endif %}"));
        at(e, 1, 9);
        assertTrue(e.getMessage().contains("compare"), e.getMessage());
    }

    @Test
    void booleanPrecedenceNotThenAndThenOr() {
        assertEquals("T", render("{% if true or false and false %}T{% else %}F{% endif %}"));
        assertEquals("F", render("{% if (true or false) and false %}T{% else %}F{% endif %}"));
        assertEquals("T", render("{% if not false and true %}T{% else %}F{% endif %}"));
        assertEquals("F", render("{% if not (false or true) %}T{% else %}F{% endif %}"));
        assertEquals("T", render("{% if not not 1 == 1 %}T{% else %}F{% endif %}"));
        assertEquals("F", render("{% if not 1 == 1 %}T{% else %}F{% endif %}"));
    }

    @Test
    void andOrShortCircuitSoRightSideErrorsAreSkipped() {
        assertEquals("F", render("{% if false and 1 < 'a' %}T{% else %}F{% endif %}"));
        assertEquals("T", render("{% if true or 1 < 'a' %}T{% else %}F{% endif %}"));
    }

    @Test
    void nestedIfs() {
        String t = "{% if a %}A{% if b %}B{% else %}b{% endif %}{% else %}a{% endif %}";
        assertEquals("AB", render(t, Map.of("a", true, "b", true)));
        assertEquals("Ab", render(t, Map.of("a", true, "b", false)));
        assertEquals("a", render(t, Map.of("a", false, "b", true)));
    }

    // ---- loops ------------------------------------------------------------------

    @Test
    void loopMetadata() {
        Map<String, Object> ctx = Map.of("xs", List.of("a", "b", "c"));
        assertEquals("a:true:false|b:false:false|c:false:true|",
                render("{% for x in xs %}{{ x }}:{{ loop.first }}:{{ loop.last }}|{% endfor %}", ctx));
        assertEquals("1 2 3 ", render("{% for x in xs %}{{ loop.index }} {% endfor %}", ctx));
        assertEquals("only:true:true", render(
                "{% for x in one %}{{ x }}:{{ loop.first }}:{{ loop.last }}{% endfor %}",
                Map.of("one", List.of("only"))));
    }

    @Test
    void emptyNullAndMissingIterablesProduceNothing() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("e", List.of());
        ctx.put("z", null);
        assertEquals("[]", render("[{% for x in e %}x{% endfor %}{% for x in z %}x{% endfor %}"
                + "{% for x in nope %}x{% endfor %}]", ctx));
    }

    @Test
    void nestedLoopsShadowAndRestoreTheOuterBinding() {
        Map<String, Object> ctx = Map.of("rows", List.of(List.of(1, 2), List.of(3)), "x", "outer");
        assertEquals("(1,2)(3)|outer", render(
                "{% for x in rows %}({% for x in x %}{{ x }}{% if not loop.last %},{% endif %}{% endfor %})"
                + "{% endfor %}|{{ x }}", ctx));
    }

    @Test
    void outerLoopIndexIsIntactAfterInnerLoopEnds() {
        Map<String, Object> ctx = Map.of("o", List.of("p", "q"), "i", List.of("a", "b"));
        assertEquals("[ab1][ab2]",
                render("{% for p in o %}[{% for q in i %}{{ q }}{% endfor %}{{ loop.index }}]{% endfor %}", ctx));
        assertEquals("p1 p2 q1 q2 ", render(
                "{% for p in o %}{% for q in i %}{{ p }}{{ loop.index }} {% endfor %}{% endfor %}", ctx));
    }

    @Test
    void loopVariableDoesNotLeakAndContextIsNotMutated() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("xs", List.of(1, 2));
        Template t = Template.parse("{% for x in xs %}{{ x }}{% endfor %}[{{ x }}][{{ loop }}]");
        assertEquals("12[][]", t.render(ctx));
        assertEquals(List.of("xs"), new ArrayList<>(ctx.keySet()));
    }

    @Test
    void loopOverNonIterableIsRenderErrorAtTheExpression() {
        TemplateRenderException e = assertThrows(TemplateRenderException.class,
                () -> render("ab\n{% for x in 5 %}{% endfor %}"));
        at(e, 2, 13);
    }

    @Test
    void loopOverMapIsRenderError() {
        assertThrows(TemplateRenderException.class,
                () -> render("{% for x in m %}{% endfor %}", Map.of("m", Map.of("a", 1))));
    }

    // ---- comments & whitespace control -------------------------------------------

    @Test
    void commentsProduceNothingAndMayContainDelimiters() {
        assertEquals("ab", render("a{# anything {{ x }} {% if #}b"));
    }

    @Test
    void whitespaceControlStripsOnlyTheMarkedSides() {
        Map<String, Object> ctx = Map.of("x", "X");
        assertEquals("a  X  b", render("a  {{ x }}  b", ctx));
        assertEquals("aX  b", render("a  {{- x }}  b", ctx));
        assertEquals("a  Xb", render("a  {{ x -}}  b", ctx));
        assertEquals("aXb", render("a  {{- x -}}  b", ctx));
        assertEquals("ab", render("a \n {#- c -#} \n b"));
    }

    @Test
    void blockTagsOnOwnLinesCanBeTrimmedAwayCleanly() {
        String t = "<ul>\n{%- for x in xs %}\n  <li>{{ x }}</li>\n{%- endfor %}\n</ul>";
        assertEquals("<ul>\n  <li>1</li>\n  <li>2</li>\n</ul>", render(t, Map.of("xs", List.of(1, 2))));
    }

    @Test
    void dashDirectlyAfterOpenerIsAlwaysATrimMarkerSoEmptyExpressionFails() {
        at(syntax("{{-}}"), 1, 4);
    }

    // ---- syntax errors with positions -------------------------------------------

    @Test
    void unterminatedDelimitersPointAtTheOpener() {
        at(syntax("Hello {{ name"), 1, 7);
        at(syntax("a\nb {% if x"), 2, 3);
        at(syntax("{# never closed"), 1, 1);
    }

    @Test
    void unclosedBlocksPointAtTheOpeningTag() {
        TemplateSyntaxException e = syntax("line1\nline2 {% if x %}\nbody");
        at(e, 2, 7);
        assertTrue(e.getMessage().contains("if"), e.getMessage());
        at(syntax("{% for a in b %}{% if a %}x{% endif %}"), 1, 1);
        at(syntax("{% if a %}x{% elif b %}y"), 1, 1);
    }

    @Test
    void strayAndMisplacedBlockTags() {
        at(syntax("a\n{% endif %}"), 2, 1);
        at(syntax("{% endfor %}"), 1, 1);
        at(syntax("{% else %}"), 1, 1);
        at(syntax("{% elif x %}"), 1, 1);
        at(syntax("{% if a %}{% else %}{% else %}{% endif %}"), 1, 21);
        at(syntax("{% if a %}{% else %}{% elif b %}{% endif %}"), 1, 21);
        at(syntax("{% for x in y %}{% endif %}"), 1, 17);
        at(syntax("{% if a %}{% for a in b %}x{% endif %}"), 1, 28);
    }

    @Test
    void unknownTagAndExtraTokensAfterTheTag() {
        at(syntax("{% frobnicate %}"), 1, 1);
        at(syntax("{% endif extra %}"), 1, 1);
        at(syntax("{% if a %}{% else extra %}{% endif %}"), 1, 19);
        at(syntax("{% if a %}{% endif extra %}"), 1, 20);
        at(syntax("{% for x in y z %}{% endfor %}"), 1, 15);
    }

    @Test
    void forHeaderErrors() {
        at(syntax("{% for x items %}{% endfor %}"), 1, 10);
        at(syntax("{% for in items %}{% endfor %}"), 1, 8);
        at(syntax("{% for x in %}{% endfor %}"), 1, 12);
        at(syntax("{% for and in items %}{% endfor %}"), 1, 8);
        at(syntax("{% for a.b in items %}{% endfor %}"), 1, 8);
    }

    @Test
    void expressionErrorsCarryExactPositions() {
        at(syntax("{{ a == }}"), 1, 8);
        at(syntax("{% if %}x{% endif %}"), 1, 6);
        at(syntax("{{ a $ b }}"), 1, 6);
        at(syntax("x\n\n  {{ 5 5 }}"), 3, 8);
        at(syntax("{{ \"abc }}"), 1, 4);
        at(syntax("{{ 'a\\q' }}"), 1, 6);
        at(syntax("{{ a = b }}"), 1, 6);
        at(syntax("{{ a ! b }}"), 1, 6);
        at(syntax("{{ (a }}"), 1, 6);
        at(syntax("{{ a.}}"), 1, 5);
        at(syntax("{{ and }}"), 1, 4);
        at(syntax("{{ a and }}"), 1, 9);
        at(syntax("{{ a | }}"), 1, 7);
        at(syntax("{{ 1.}}"), 1, 5);
        at(syntax("{{ 0.1 + 0 }}"), 1, 8);
    }

    @Test
    void positionsCountLinesAcrossMultilineTags() {
        at(syntax("{% if a\n  and $ %}x{% endif %}"), 2, 7);
    }

    @Test
    void errorMessageEndsWithTheLocation() {
        TemplateSyntaxException e = syntax("ab\n{{ a $ b }}");
        assertTrue(e.getMessage().endsWith("(line 2, column 6)"), e.getMessage());
    }

    @Test
    void nestingDeeperThanTheLimitIsASyntaxErrorNotAStackOverflow() {
        syntax("{% if a %}".repeat(101) + "{% endif %}".repeat(101));
        syntax("{{ " + "(".repeat(500) + "a" + ")".repeat(500) + " }}");
        syntax("{{ " + "not ".repeat(500) + "a }}");
        String ok = "{% if a %}".repeat(100) + "x" + "{% endif %}".repeat(100);
        assertEquals("x", render(ok, Map.of("a", true)));
    }

    // ---- reuse and robustness ---------------------------------------------------

    @Test
    void parsedTemplateIsReusableWithDifferentContexts() {
        Template t = Template.parse("Hi {{ who | upper }}{% if n %}!{% endif %}");
        assertEquals("Hi ADA!", t.render(Map.of("who", "ada", "n", 1)));
        assertEquals("Hi BOB", t.render(Map.of("who", "bob")));
        assertEquals("Hi ADA!", t.render(Map.of("who", "ada", "n", 1)));
    }

    @Test
    void nullArgumentsAreRejected() {
        Template t = Template.parse("x");
        assertThrows(NullPointerException.class, () -> t.render(null));
        assertThrows(NullPointerException.class, () -> Template.parse(null));
    }

    @Test
    void randomTextWithoutOpenersIsReproducedExactly() {
        Random rnd = new Random(7);
        String alphabet = "ab {}%#-\n\t<>&";
        int checked = 0;
        for (int i = 0; i < 2000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(30);
            for (int j = 0; j < len; j++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            String s = sb.toString();
            if (s.contains("{{") || s.contains("{%") || s.contains("{#")) {
                continue;
            }
            assertEquals(s, render(s), "input: " + s);
            checked++;
        }
        assertTrue(checked > 500, "too few usable samples: " + checked);
    }

    @Test
    void fuzzGarbageOnlyEverYieldsTemplateExceptions() {
        Random rnd = new Random(20261117);
        String[] pieces = {"{{", "}}", "{%", "%}", "{#", "#}", "-", " ", "if ", "elif ", "else", "endif", "for ",
            "endfor", "x", "in ", "xs", "a.b", "1", "1.5", "'s'", "\"t\"", "==", "!=", "<", ">=", "and ", "or ",
            "not ", "(", ")", "|", "upper", "default('d')", "join(',')", "length", "raw", ",", "\\", "\n", "'",
            "\"", "$", "true", "null"};
        Map<String, Object> ctx = Map.of("x", "v", "xs", List.of(1, 2), "a", Map.of("b", 3));
        int parsed = 0;
        for (int i = 0; i < 6000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + rnd.nextInt(14);
            for (int j = 0; j < n; j++) {
                sb.append(pieces[rnd.nextInt(pieces.length)]);
            }
            try {
                Template.parse(sb.toString()).render(ctx);
                parsed++;
            } catch (TemplateException expected) {
                assertTrue(expected.getLine() >= 1 && expected.getColumn() >= 1,
                        sb + " -> " + expected.getMessage());
            }
        }
        assertTrue(parsed > 50, "fuzz should also produce valid templates, got " + parsed);
    }

    @Test
    void realisticPage() {
        String t = """
                <h1>{{ title | upper }}</h1>
                {%- if items %}
                <ul>
                {%- for it in items %}
                  <li class="{% if loop.first %}first{% elif loop.last %}last{% else %}mid{% endif %}">{{ loop.index }}. {{ it.name }} ({{ it.price | default('free') }})</li>
                {%- endfor %}
                </ul>
                {%- else %}
                <p>none</p>
                {%- endif %}
                """;
        Map<String, Object> it1 = Map.of("name", "Tea & Cake", "price", 4.5);
        Map<String, Object> it2 = Map.of("name", "Water");
        Map<String, Object> it3 = Map.of("name", "Jam", "price", 3);
        String out = render(t, Map.of("title", "menu", "items", List.of(it1, it2, it3)));
        assertEquals("""
                <h1>MENU</h1>
                <ul>
                  <li class="first">1. Tea &amp; Cake (4.5)</li>
                  <li class="mid">2. Water (free)</li>
                  <li class="last">3. Jam (3)</li>
                </ul>
                """, out);
        assertEquals("<h1>X</h1>\n<p>none</p>\n", render(t, Map.of("title", "x")));
    }
}
