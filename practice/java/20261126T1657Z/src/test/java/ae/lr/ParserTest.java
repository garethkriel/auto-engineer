package ae.lr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ParserTest {

    static LrParser parser(String g, Mode m) {
        return new LrParser(LrTable.build(Grammar.parse(g), m));
    }

    static List<String> toks(String s) {
        return s.isBlank() ? List.of() : List.of(s.trim().split("\\s+"));
    }

    @Test
    void parsesExpressionWithPrecedenceAndLeftAssociativity() {
        LrParser p = parser(GrammarTest.EXPR, Mode.LALR);
        assertEquals("(E (E (T (F id))) + (T (T (F id)) * (F id)))",
            p.parse(toks("id + id * id")).toString());
        assertEquals("(E (E (E (T (F id))) + (T (F id))) + (T (F id)))",
            p.parse(toks("id + id + id")).toString());
        assertEquals("(E (T (F ( (E (E (T (F id))) + (T (F id))) ))))",
            p.parse(toks("( id + id )")).toString());
    }

    @Test
    void allThreeModesAgreeOnTheExpressionGrammar() {
        String input = "( id + id ) * id + id * ( id )";
        String expected = parser(GrammarTest.EXPR, Mode.LR1).parse(toks(input)).toString();
        assertEquals(expected, parser(GrammarTest.EXPR, Mode.LALR).parse(toks(input)).toString());
        assertEquals(expected, parser(GrammarTest.EXPR, Mode.SLR).parse(toks(input)).toString());
    }

    @Test
    void treeRecordsProductionIndicesAndYield() {
        Tree t = parser(GrammarTest.EXPR, Mode.LALR).parse(toks("id * id"));
        assertEquals(2, t.production());              // E -> T
        assertEquals(3, t.children().get(0).production()); // T -> T * F
        assertEquals(List.of("id", "*", "id"), t.yield());
        assertTrue(t.children().get(0).children().get(1).isLeaf());
        assertEquals(-1, t.children().get(0).children().get(1).production());
    }

    @Test
    void epsilonNodesAreNotLeaves() {
        LrParser p = parser("S -> A b\nA -> ε | a\n", Mode.LALR);
        Tree t = p.parse(toks("b"));
        assertEquals("(S (A) b)", t.toString());
        assertFalse(t.children().get(0).isLeaf());
        assertEquals(List.of("b"), t.yield());
        assertEquals("(S (A a) b)", p.parse(toks("a b")).toString());
    }

    @Test
    void emptyInputForNullableStartSymbol() {
        LrParser p = parser("S -> a S | ε\n", Mode.LALR);
        assertEquals("(S)", p.parse(List.of()).toString());
        assertEquals("(S a (S a (S)))", p.parse(toks("a a")).toString());
    }

    @Test
    void emptyInputIsAnErrorAtPositionZeroWhenNotNullable() {
        LrParser p = parser("S -> a b\n", Mode.LALR);
        LrParseException e = assertThrows(LrParseException.class, () -> p.parse(List.of()));
        assertEquals(0, e.position());
        assertEquals("$", e.found());
        assertEquals(Set.of("a"), e.expected());
    }

    @Test
    void errorReportsPositionFoundTokenAndSortedExpectedSet() {
        LrParser p = parser(GrammarTest.EXPR, Mode.LR1);
        LrParseException e = assertThrows(LrParseException.class, () -> p.parse(toks("id + * id")));
        assertEquals(2, e.position());
        assertEquals("*", e.found());
        assertEquals(List.of("(", "id"), List.copyOf(e.expected()));
        assertTrue(e.getMessage().contains("token 2"), e.getMessage());
        assertTrue(e.getMessage().contains("'*'"), e.getMessage());
        assertTrue(e.getMessage().contains("[(, id]"), e.getMessage());
    }

    @Test
    void errorAtEndOfInputUsesDollar() {
        LrParser p = parser(GrammarTest.EXPR, Mode.LR1);
        LrParseException e = assertThrows(LrParseException.class, () -> p.parse(toks("( id + id")));
        assertEquals(4, e.position());
        assertEquals("$", e.found());
        assertEquals(List.of(")", "*", "+"), List.copyOf(e.expected()));
    }

    @Test
    void trailingTokensAreAnError() {
        LrParser p = parser(GrammarTest.EXPR, Mode.LALR);
        LrParseException e = assertThrows(LrParseException.class, () -> p.parse(toks("id id")));
        assertEquals(1, e.position());
        assertEquals("id", e.found());
    }

    @Test
    void unknownTokenAndNonterminalNameAreSyntaxErrors() {
        LrParser p = parser(GrammarTest.EXPR, Mode.LALR);
        assertEquals(1, assertThrows(LrParseException.class, () -> p.parse(toks("id ?"))).position());
        // the nonterminal name "E" is not a token
        assertEquals(0, assertThrows(LrParseException.class, () -> p.parse(toks("E"))).position());
    }

    @Test
    void dollarTokenIsRejectedAsIllegalArgument() {
        LrParser p = parser(GrammarTest.EXPR, Mode.LALR);
        assertThrows(IllegalArgumentException.class, () -> p.parse(toks("id $")));
        assertThrows(NullPointerException.class, () -> p.parse(null));
    }

    @Test
    void refusesConflictedTable() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> parser("E -> E + E | id\n", Mode.LALR));
        assertTrue(e.getMessage().contains("1 conflict"), e.getMessage());
        // control: the same language, unambiguous, loads
        parser("E -> E + id | id\n", Mode.LALR);
    }

    @Test
    void lalrAndSlrGrammarDifference() {
        String g = TableTest.LR_NOT_SLR;
        assertThrows(IllegalArgumentException.class, () -> parser(g, Mode.SLR));
        LrParser p = parser(g, Mode.LALR);
        assertEquals("(S (L * (R (L id))) = (R (L id)))", p.parse(toks("* id = id")).toString());
        assertEquals("(S (R (L id)))", p.parse(toks("id")).toString());
    }

    @Test
    void lr1OnlyGrammarParsesOnlyWithLr1() {
        String g = TableTest.LR1_NOT_LALR;
        assertThrows(IllegalArgumentException.class, () -> parser(g, Mode.LALR));
        LrParser p = parser(g, Mode.LR1);
        assertEquals("(S a (E e) a)", p.parse(toks("a e a")).toString());
        assertEquals("(S a (F e) b)", p.parse(toks("a e b")).toString());
        assertEquals("(S b (F e) a)", p.parse(toks("b e a")).toString());
        assertEquals("(S b (E e) b)", p.parse(toks("b e b")).toString());
        assertEquals(2, assertThrows(LrParseException.class, () -> p.parse(toks("a e e"))).position());
    }

    @Test
    void deepRightRecursionDoesNotOverflowTheStack() {
        LrParser p = parser("L -> x L | x\n", Mode.LALR);
        List<String> in = java.util.Collections.nCopies(50_000, "x");
        assertEquals(in, p.parse(in).yield());
    }

    @Test
    void parserIsReusableAfterAnError() {
        LrParser p = parser(GrammarTest.EXPR, Mode.LALR);
        assertThrows(LrParseException.class, () -> p.parse(toks("id +")));
        assertEquals("(E (T (F id)))", p.parse(toks("id")).toString());
    }
}
