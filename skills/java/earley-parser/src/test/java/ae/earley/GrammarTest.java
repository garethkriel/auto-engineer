package ae.earley;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GrammarTest {

    @Test
    void parsesRulesInOrderWithStartSymbol() {
        Grammar g = Grammar.parse("S -> A 'b' | 'c'\nA -> 'a' A |\n");
        assertEquals("S", g.start());
        assertEquals(List.of("S", "A"), g.nonTerminals());
        List<String> rules = g.rules().stream().map(Rule::toString).toList();
        assertEquals(List.of("S -> A 'b'", "S -> 'c'", "A -> 'a' A", "A ->"), rules);
    }

    @Test
    void continuationLinesAndRepeatedNamesAccumulate() {
        Grammar g = Grammar.parse("S -> 'a'\n  | 'b'\nT -> 'x'\nS -> 'c'\n| 'd'");
        List<String> rules = g.rules().stream().map(Rule::toString).toList();
        assertEquals(List.of("S -> 'a'", "S -> 'b'", "T -> 'x'", "S -> 'c'", "S -> 'd'"), rules);
        assertEquals(List.of(0, 1, 3, 4), g.ruleIndexes("S"));
        assertEquals(List.of(2), g.ruleIndexes("T"));
    }

    @Test
    void leadingEpsilonAlternative() {
        Grammar g = Grammar.parse("A -> | 'a'");
        assertEquals(List.of("A ->", "A -> 'a'"), g.rules().stream().map(Rule::toString).toList());
    }

    @Test
    void commentsAreStrippedButNotInsideQuotes() {
        Grammar g = Grammar.parse("# header\nS -> '#' 'a' # trailing\n\n   # indented comment\n");
        assertEquals(List.of("S -> '#' 'a'"), g.rules().stream().map(Rule::toString).toList());
    }

    @Test
    void terminalEscapes() {
        Grammar g = Grammar.parse("S -> '\\'' '\\\\' ' '");
        Rule r = g.rules().get(0);
        assertEquals(List.of(new Symbol.Terminal('\''), new Symbol.Terminal('\\'), new Symbol.Terminal(' ')), r.rhs());
        assertEquals("S -> '\\'' '\\\\' ' '", r.toString());
    }

    @Test
    void terminalCanBeSupplementaryCodePoint() {
        int smile = 0x1F600;
        Grammar g = Grammar.parse("S -> '" + new String(Character.toChars(smile)) + "'");
        assertEquals(new Symbol.Terminal(smile), g.rules().get(0).rhs().get(0));
    }

    @Test
    void nullableSetIsTransitiveAndSorted() {
        Grammar g = Grammar.parse("S -> A B 'x'\nA -> B B |\nB -> C\nC ->\nD -> D 'd'\nE -> A C");
        assertEquals(Set.of("A", "B", "C", "E"), g.nullable());
        assertEquals(List.of("A", "B", "C", "E"), List.copyOf(g.nullable()));
    }

    @Test
    void nullableNeedsEveryRhsSymbolNullable() {
        Grammar g = Grammar.parse("S -> A 'a'\nA -> B\nB -> 'b' |  C\nC -> C");
        assertEquals(Set.of(), g.nullable());
    }

    private static void assertError(String text, String expectedPrefix) {
        GrammarException e = assertThrows(GrammarException.class, () -> Grammar.parse(text));
        assertTrue(e.getMessage().startsWith(expectedPrefix), () -> "message was: " + e.getMessage());
    }

    @Test
    void errorsCarryLineNumbers() {
        assertError("", "line 0: no rules");
        assertError("# only comments\n\n", "line 0: no rules");
        assertError("S -> 'a'\nT 'b'", "line 2:");
        assertError("S -> 'a'\n\n  -> 'b'", "line 3:");
        assertError("S -> 'ab'", "line 1:");
        assertError("S -> ''", "line 1:");
        assertError("S -> 'a", "line 1:");
        assertError("S -> 'a' $", "line 1:");
        assertError("S -> '\\n'", "line 1:");
        assertError("| 'a'", "line 1:");
        assertError("S -> 'a'\nT -> U", "line 2: undefined nonterminal U");
        assertError("S -> T\nT -> 'a'\nU -> V", "line 3: undefined nonterminal V");
    }

    @Test
    void undefinedNonterminalReportsFirstUse() {
        assertError("S -> X 'a'\nT -> X", "line 1: undefined nonterminal X");
    }

    @Test
    void rulesListIsUnmodifiable() {
        Grammar g = Grammar.parse("S -> 'a'");
        assertThrows(UnsupportedOperationException.class, () -> g.rules().clear());
        assertThrows(UnsupportedOperationException.class, () -> g.nonTerminals().clear());
    }

    @Test
    void nullInputRejected() {
        assertThrows(NullPointerException.class, () -> Grammar.parse(null));
    }
}
