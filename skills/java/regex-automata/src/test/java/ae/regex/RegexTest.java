package ae.regex;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class RegexTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "`abc`|`abc`|true",
        "`abc`|`abcd`|false",
        "`abc`|``|false",
        "``|``|true",
        "``|`a`|false",
        "`a|b`|`b`|true",
        "`a|b`|`ab`|false",
        "`a|`|``|true",
        "`(a|b)*abb`|`aabb`|true",
        "`(a|b)*abb`|`abab`|false",
        "`a+`|``|false",
        "`a+`|`aaa`|true",
        "`a?b`|`b`|true",
        "`a?b`|`ab`|true",
        "`a?b`|`aab`|false",
        "`a{3}`|`aaa`|true",
        "`a{3}`|`aa`|false",
        "`a{3}`|`aaaa`|false",
        "`a{2,}`|`a`|false",
        "`a{2,}`|`aaaaa`|true",
        "`a{1,3}`|`aaa`|true",
        "`a{1,3}`|`aaaa`|false",
        "`a{0,0}`|``|true",
        "`(ab){2}`|`abab`|true",
        "`(ab){2}`|`aab`|false",
        "`[a-c]+`|`abcabc`|true",
        "`[a-c]+`|`abd`|false",
        "`[^a-c]`|`d`|true",
        "`[^a-c]`|`b`|false",
        "`[a-]`|`-`|true",
        "`[-a]`|`-`|true",
        "`[\\]]`|`]`|true",
        "`\\d+`|`2026`|true",
        "`\\d+`|`20x6`|false",
        "`\\D`|`x`|true",
        "`\\w+`|`a_Z9`|true",
        "`\\w+`|`a-b`|false",
        "`\\W`|`-`|true",
        "`\\s\\S`|` x`|true",
        "`[\\d_]+`|`1_2`|true",
        "`a\\.b`|`a.b`|true",
        "`a\\.b`|`axb`|false",
        "`.`|`x`|true",
        "`.*`|`any thing`|true",
        "`\\(\\)`|`()`|true",
        "`a}`|`a}`|true",
        "`]`|`]`|true",
    })
    void matchesTable(String pattern, String input, boolean expected) {
        // CsvSource turns a missing/blank quoted value into null only for unquoted empties; backticks keep ""
        Regex r = Regex.compile(pattern);
        assertEquals(expected, r.matches(input), pattern + " vs " + input);
        assertEquals(expected, Nfa.build(RegexParser.parse(pattern)).matches(input), "nfa " + pattern + " vs " + input);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "`(a`|0|unclosed group",
        "`ab(c`|2|unclosed group",
        "`a)`|1|unmatched",
        "`*a`|0|nothing to repeat",
        "`a|*`|2|nothing to repeat",
        "`{2}`|0|nothing to repeat",
        "`+`|0|nothing to repeat",
        "`a**`|2|dangling",
        "`a*?`|2|dangling",
        "`a+{2}`|2|dangling",
        "`a{`|1|malformed quantifier",
        "`a{x}`|1|malformed quantifier",
        "`a{2`|1|malformed quantifier",
        "`a{2,1}`|1|bounds",
        "`a{1001}`|1|bounds",
        "`[a`|0|unclosed class",
        "`[z-a]`|1|range",
        "`[]`|1|empty class",
        "`[^]`|2|empty class",
        "`[a[b]]`|2|nested",
        "`[a-\\d]`|3|range",
        "`\\`|0|trailing backslash",
        "`a\\q`|1|unknown escape",
        "`^a`|0|anchor",
        "`a$`|1|anchor",
    })
    void syntaxErrorsReportIndexAndReason(String pattern, int index, String reasonFragment) {
        RegexSyntaxException e = assertThrows(RegexSyntaxException.class, () -> Regex.compile(pattern), pattern);
        assertEquals(index, e.index(), pattern + " -> " + e.getMessage());
        assertTrue(e.getMessage().contains(reasonFragment), pattern + " -> " + e.getMessage());
    }

    @Test
    void escapesAndNegatedClassesCoverNewlines() {
        assertTrue(Regex.compile("[^a]").matches("\n"));
        assertTrue(Regex.compile("\\t\\n").matches("\t\n"));
        assertTrue(Regex.compile("\\s+").matches(" \t\n\u000B\f\r"));
        assertFalse(Regex.compile("\\s").matches("\u00A0"));
    }

    @Test
    void repeatOfRepeatTooLargeIsRejectedNotAnOutOfMemory() {
        RegexSyntaxException e = assertThrows(RegexSyntaxException.class,
                () -> Regex.compile("((((a{1000}){1000}){1000}){1000})"));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
    }

    @Test
    void dotExcludesExactlyTheFiveLineTerminators() {
        // Only the five Java line terminators are excluded from '.'; every other char matches.
        Regex dot = Regex.compile(".");
        for (char c : new char[] {'\u0000', '\t', '\u000B', '\u000C', ' ', '\u0084', '\u0086', '‧', '‪', '￿'}) {
            assertTrue(dot.matches(String.valueOf(c)), "U+" + Integer.toHexString(c));
        }
        for (char c : new char[] {'\n', '\r', '\u0085', ' ', ' '}) {
            assertFalse(dot.matches(String.valueOf(c)), "U+" + Integer.toHexString(c));
        }
    }

    @Test
    void findIsLeftmostThenLongest() {
        Regex r = Regex.compile("a|ab|abc");
        Optional<Regex.Match> m = r.find("xxabcx");
        assertTrue(m.isPresent());
        assertEquals(2, m.get().start());
        assertEquals(5, m.get().end()); // longest, unlike Java's leftmost-first which would give "a"
    }

    @Test
    void findHonoursFromAndReportsNoMatch() {
        Regex r = Regex.compile("[0-9]+");
        assertEquals(new Regex.Match(3, 5), r.find("ab 12 345", 3).orElseThrow());
        assertEquals(new Regex.Match(6, 9), r.find("ab 12 345", 5).orElseThrow());
        assertTrue(r.find("abc").isEmpty());
        assertTrue(r.find("abc", 3).isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> r.find("abc", 4));
    }

    @Test
    void findCanReturnEmptyMatchAtLeftmostPosition() {
        Regex r = Regex.compile("b*");
        assertEquals(new Regex.Match(0, 0), r.find("abb").orElseThrow());
        assertEquals(new Regex.Match(1, 3), r.find("abb", 1).orElseThrow());
    }

    @Test
    void findAllReturnsNonOverlappingMatchesAndAdvancesOverEmpties() {
        assertEquals(List.of(new Regex.Match(0, 2), new Regex.Match(4, 7)), // "aa baaa": a at 0,1 then 4,5,6
                Regex.compile("a+").findAll("aa baaa"));
        // "abba": empty@0, "bb"@[1,3), then an empty match at 3 (before the last 'a', as in java.util.regex), empty@4
        assertEquals(List.of(new Regex.Match(0, 0), new Regex.Match(1, 3), new Regex.Match(3, 3), new Regex.Match(4, 4)),
                Regex.compile("b*").findAll("abba"));
        assertEquals(List.of(), Regex.compile("z").findAll("abc"));
    }

    @Test
    void emptyLanguageAndExamples() {
        Regex none = Regex.compile("a").intersect(Regex.compile("b"));
        assertTrue(none.isEmptyLanguage());
        assertTrue(none.example().isEmpty());
        assertEquals(Optional.of(""), Regex.compile("a*").example());
        assertEquals(Optional.of("aaa"), Regex.compile("a{3,5}").example());
        assertEquals(Optional.of("a"), Regex.compile("[a-c]|d").example());
        assertEquals(Optional.of("0"), Regex.compile("[0-9]+").example());
    }

    @Test
    void equivalenceAndShortestWitness() {
        assertTrue(Regex.compile("(a|b)*").equivalent(Regex.compile("(a*b*)*")));
        assertTrue(Regex.compile("a(b|c)").equivalent(Regex.compile("ab|ac")));
        assertTrue(Regex.compile("a{2,3}").equivalent(Regex.compile("aa|aaa")));
        assertTrue(Regex.compile("[^a]").equivalent(Regex.compile("[^a]|[^a-z]")));
        assertEquals(Optional.empty(), Regex.compile("a+").distinguish(Regex.compile("aa*")));
        assertEquals(Optional.of(""), Regex.compile("a*").distinguish(Regex.compile("a+")));
        assertEquals(Optional.of("aa"), Regex.compile("a{1,2}").distinguish(Regex.compile("a")));
        // shortest witness: "ab" is accepted by the left only; nothing of length < 2 differs
        assertEquals(Optional.of("ab"), Regex.compile("a|ab").distinguish(Regex.compile("a")));
    }

    @Test
    void countAcceptedStringsPerLength() {
        Regex r = Regex.compile("[ab]*");
        assertEquals(java.math.BigInteger.ONE, r.count(0));
        assertEquals(java.math.BigInteger.valueOf(2), r.count(1));
        assertEquals(java.math.BigInteger.valueOf(1024), r.count(10));
        // '.' accepts 65536 - 5 code units
        Regex dots = Regex.compile("...");
        assertEquals(java.math.BigInteger.valueOf(65531).pow(3), dots.count(3));
        assertEquals(java.math.BigInteger.ZERO, dots.count(2));
        assertEquals(java.math.BigInteger.valueOf(3), Regex.compile("a|b|c").count(1));
        assertEquals(java.math.BigInteger.valueOf(10), Regex.compile("\\d").count(1));
    }

    @Test
    void complementAndIntersectionBasics() {
        Regex a = Regex.compile("a*");
        Regex notA = a.complement();
        assertFalse(notA.matches(""));
        assertFalse(notA.matches("aaa"));
        assertTrue(notA.matches("ab"));
        assertTrue(a.complement().complement().equivalent(a));
        Regex both = Regex.compile("[ab]+").intersect(Regex.compile("a.*"));
        assertTrue(both.matches("abba"));
        assertFalse(both.matches("baab"));
        assertFalse(both.matches("ac"));
    }
}
