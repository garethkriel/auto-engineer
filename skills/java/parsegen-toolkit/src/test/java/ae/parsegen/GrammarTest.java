package ae.parsegen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GrammarTest {
    @Test
    void parsesDragonGrammarInRuleOrder() {
        Grammar g = Grammar.parse(Fx.LL);
        assertEquals("E", g.start());
        assertEquals(List.of("E", "E1", "T", "T1", "F"), List.copyOf(g.nonterminals()));
        assertEquals(List.of("(", ")", "*", "+", "id"), List.copyOf(g.terminals()));
        assertEquals(8, g.productions().size());
        Production p = g.production(1);
        assertEquals("E1", p.lhs());
        assertEquals(List.of("+", "T", "E1"), p.rhs());
        assertEquals(List.of(), g.production(2).rhs());
        assertEquals(1, p.id());
        assertEquals(List.of(1, 2), g.productionsOf("E1").stream().map(Production::id).toList());
        assertTrue(g.isNonterminal("T1"));
        assertFalse(g.isNonterminal("id"));
    }

    @Test
    void sameLhsOnSeveralLinesAppendsAlternatives() {
        Grammar g = Grammar.parse("S -> a\nT -> b\nS -> c\n");
        assertEquals(List.of(0, 2), g.productionsOf("S").stream().map(Production::id).toList());
        assertEquals(List.of("c"), g.production(2).rhs());
        // ids follow textual order even when a LHS repeats
        assertEquals("T", g.production(1).lhs());
    }

    @Test
    void epsilonSpellings() {
        Grammar g = Grammar.parse("S -> a S | ε\n");
        assertEquals(List.of(), g.production(1).rhs());
        assertEquals(List.of("a"), List.copyOf(g.terminals()));
    }

    @Test
    void rejectsMalformedInput() {
        assertMsg("no rules", "# nothing\n\n");
        assertMsg("line 1", "S a b");
        assertMsg("line 2", "S -> a\nT -> | b");
        assertMsg("empty alternative", "S -> a |");
        assertMsg("empty alternative", "S -> a | | b");
        assertMsg("empty alternative", "S ->");
        assertMsg("epsilon", "S -> a eps");
        assertMsg("reserved", "S -> a $");
        assertMsg("reserved", "$S -> a");
        assertMsg("unproductive", "S -> a | A\nA -> A b");
        assertMsg("unproductive", "S -> S");
    }

    @Test
    void controlForRejectionsDiffersByTheDefectOnly() {
        // productive twin of the unproductive grammar above loads
        Grammar ok = Grammar.parse("S -> a | A\nA -> A b | c");
        assertEquals(2, ok.nonterminals().size());
        // multi-symbol left-hand side
        assertMsg("left-hand side", "S T -> a");
    }

    private static void assertMsg(String fragment, String text) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Grammar.parse(text));
        assertTrue(e.getMessage().contains(fragment), () -> "message '" + e.getMessage() + "' lacks '" + fragment + "'");
    }
}
