package ae.earley;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecognizerTest {

    static boolean accepts(String grammar, String input) {
        return new EarleyParser(Grammar.parse(grammar)).parse(input).accepted();
    }

    static final String PARENS = "S -> '(' S ')' S |";
    static final String LEFT = "S -> S 'a' | 'a'";
    static final String RIGHT = "S -> 'a' S | 'a'";
    static final String PALINDROME = "S -> 'a' S 'a' | 'b' S 'b' | 'a' | 'b' |";
    static final String ARITH =
        "E -> E '+' T | T\nT -> T '*' F | F\nF -> '(' E ')' | 'n'";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "'()'|true", "''|true", "'(())()'|true", "'()()()'|true", "'(()'|false", "')('|false",
        "'())'|false", "'(a)'|false"})
    void balancedParentheses(String input, boolean expected) {
        assertEquals(expected, accepts(PARENS, input));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "'a'|true", "'aaaa'|true", "''|false", "'ab'|false", "'aaab'|false"})
    void leftAndRightRecursionAcceptTheSameLanguage(String input, boolean expected) {
        assertEquals(expected, accepts(LEFT, input));
        assertEquals(expected, accepts(RIGHT, input));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "''|true", "'a'|true", "'abba'|true", "'abbba'|true", "'ababa'|true", "'ab'|false",
        "'abab'|false", "'aabb'|false", "'abbaa'|false"})
    void palindromesIncludingEmptyMiddle(String input, boolean expected) {
        assertEquals(expected, accepts(PALINDROME, input));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "'n'|true", "'n+n*n'|true", "'(n+n)*n'|true", "'((n))'|true", "'n+'|false", "'+n'|false",
        "'n n'|false", "'(n'|false", "'n)'|false", "''|false"})
    void arithmeticGrammar(String input, boolean expected) {
        assertEquals(expected, accepts(ARITH, input));
    }

    @Test
    void nullableChainBeforeAndAfterTerminal() {
        String g = "S -> A B C 'x' A B C\nA ->\nB -> A A\nC -> B | 'c'";
        assertTrue(accepts(g, "x"));
        assertTrue(accepts(g, "cxc"));
        assertTrue(accepts(g, "xc"));
        assertTrue(accepts(g, "cx"));
        assertFalse(accepts(g, "ccxc"));
        assertFalse(accepts(g, ""));
    }

    @Test
    void emptyInputAcceptedOnlyWhenStartNullable() {
        assertTrue(accepts("S -> A A\nA -> 'a' |", ""));
        assertFalse(accepts("S -> A A\nA -> 'a'", ""));
        assertTrue(accepts("S ->", ""));
        assertFalse(accepts("S ->", "a"));
    }

    @Test
    void nullableSymbolFollowedByItselfAcrossSets() {
        // The nullable fix must also fire when a nullable symbol appears mid-rule after a real token.
        String g = "S -> 'a' N 'b' N 'c'\nN -> M\nM -> | 'm'";
        assertTrue(accepts(g, "abc"));
        assertTrue(accepts(g, "ambc"));
        assertTrue(accepts(g, "abmc"));
        assertTrue(accepts(g, "ambmc"));
        assertFalse(accepts(g, "ammbc"));
    }

    @Test
    void mutuallyRecursiveNonterminals() {
        String g = "Even -> 'a' Odd |\nOdd -> 'a' Even";
        for (int n = 0; n < 9; n++) {
            assertEquals(n % 2 == 0, accepts(g, "a".repeat(n)), "n=" + n);
        }
    }

    @Test
    void supplementaryCodePointsCountAsOneSymbol() {
        String smile = new String(Character.toChars(0x1F600));
        String g = "S -> '" + smile + "' S |";
        ParseResult ok = new EarleyParser(Grammar.parse(g)).parse(smile + smile + smile);
        assertTrue(ok.accepted());
        ParseResult bad = new EarleyParser(Grammar.parse(g)).parse(smile + "x" + smile);
        assertFalse(bad.accepted());
        assertEquals(1, bad.errorOffset()); // offset counts code points, not UTF-16 units
        assertEquals(Set.of(0x1F600), bad.expected());
    }

    @Test
    void errorOffsetAndExpectedInTheMiddle() {
        ParseResult r = new EarleyParser(Grammar.parse(ARITH)).parse("n+*n");
        assertFalse(r.accepted());
        assertEquals(2, r.errorOffset());
        assertEquals(new TreeSet<>(Set.of((int) '(', (int) 'n')), r.expected());
    }

    @Test
    void errorAtEndMeansPrefixOfSentence() {
        ParseResult r = new EarleyParser(Grammar.parse(ARITH)).parse("n+");
        assertEquals(2, r.errorOffset());
        assertEquals(new TreeSet<>(Set.of((int) '(', (int) 'n')), r.expected());
        ParseResult r2 = new EarleyParser(Grammar.parse(ARITH)).parse("(n");
        assertEquals(2, r2.errorOffset());
        assertEquals(new TreeSet<>(Set.of((int) '+', (int) '*', (int) ')')), r2.expected());
    }

    @Test
    void errorAtFirstSymbol() {
        ParseResult r = new EarleyParser(Grammar.parse("S -> 'a' 'b'")).parse("b");
        assertEquals(0, r.errorOffset());
        assertEquals(Set.of((int) 'a'), r.expected());
    }

    @Test
    void trailingGarbageAfterCompleteSentence() {
        ParseResult r = new EarleyParser(Grammar.parse("S -> 'a' 'b'")).parse("abb");
        assertFalse(r.accepted());
        assertEquals(2, r.errorOffset());
        assertEquals(Set.of(), r.expected());
    }

    @Test
    void acceptedHasNoError() {
        ParseResult r = new EarleyParser(Grammar.parse(PARENS)).parse("()");
        assertEquals(-1, r.errorOffset());
        assertEquals(Set.of(), r.expected());
    }

    @Test
    void chartStaysLinearForLeftRecursion() {
        EarleyParser p = new EarleyParser(Grammar.parse(LEFT));
        int n1 = 500;
        int n2 = 2000;
        ParseResult r1 = p.parse("a".repeat(n1));
        ParseResult r2 = p.parse("a".repeat(n2));
        assertTrue(r1.accepted() && r2.accepted());
        // Each set holds the same handful of items (S->.Sa, S->.a, S->S.a, S->a., S->Sa.) = 5 per token.
        assertTrue(r2.chartItems() <= 6 * n2, "items=" + r2.chartItems());
        assertTrue(r2.chartItems() <= 4.5 * r1.chartItems(), "ratio " + r2.chartItems() + "/" + r1.chartItems());
    }

    @Test
    void parseIsRepeatable() {
        EarleyParser p = new EarleyParser(Grammar.parse(PARENS));
        assertTrue(p.parse("()").accepted());
        assertFalse(p.parse("(").accepted());
        assertTrue(p.parse("()").accepted());
    }

    @Test
    void nullsRejected() {
        assertThrows(NullPointerException.class, () -> new EarleyParser(null));
        assertThrows(NullPointerException.class, () -> new EarleyParser(Grammar.parse("S ->")).parse(null));
    }
}
