package ae.parsegen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlrTest {
    private final Grammar g = Grammar.parse(Fx.LR);
    private final Slr slr = Slr.build(g);

    @Test
    void expressionGrammarHasTwelveStatesAndNoConflicts() {
        assertEquals(12, slr.stateCount()); // dragon book figure 4.31
        assertTrue(slr.conflicts().isEmpty());
        assertTrue(slr.isSlr1());
    }

    @Test
    void startStateActionsAndAccept() {
        assertEquals('S', slr.action(0, "id").kind());
        assertEquals('S', slr.action(0, "(").kind());
        assertNull(slr.action(0, "+"));
        assertNull(slr.action(0, "$"));
        int afterE = slr.go(0, "E");
        assertEquals('A', slr.action(afterE, "$").kind());
        assertEquals('S', slr.action(afterE, "+").kind());
        assertNull(slr.action(afterE, "*"));
    }

    @Test
    void reductionsOnlyOnFollow() {
        int afterId = slr.go(0, "id");
        // F -> id . reduces (production 5) on FOLLOW(F) = { $ ) * + } and nothing else
        for (String t : List.of("$", ")", "*", "+")) {
            assertEquals(new Slr.Action('R', 5), slr.action(afterId, t), t);
        }
        assertNull(slr.action(afterId, "id"));
        assertNull(slr.action(afterId, "("));
        // E -> T . (production 1) reduces on FOLLOW(E) = { $ ) + } (E -> E + T), but shifts * for T -> T . * F
        int afterT = slr.go(0, "T");
        assertEquals(new Slr.Action('R', 1), slr.action(afterT, "$"));
        assertEquals(new Slr.Action('R', 1), slr.action(afterT, ")"));
        assertEquals('S', slr.action(afterT, "*").kind());
        assertEquals(new Slr.Action('R', 1), slr.action(afterT, "+"));
        assertNull(slr.action(afterT, "id"));
    }

    @Test
    void parsesPrecedenceAndLeftAssociativity() {
        Tree t = slr.parse(Fx.toks("id + id * id"));
        assertEquals("(E (E (T (F id))) + (T (T (F id)) * (F id)))", t.sexp());
        assertEquals(Fx.toks("id + id * id"), t.yield());
        t.validate(g);
        assertEquals("(E (E (E (T (F id))) + (T (F id))) + (T (F id)))", slr.parse(Fx.toks("id + id + id")).sexp());
    }

    @Test
    void parsesNestedParentheses() {
        Tree t = slr.parse(Fx.toks("( ( id ) ) * id"));
        String id = "(E (T (F id)))";
        String inner = "(E (T (F ( " + id + " ))))";
        String f1 = "(F ( " + inner + " ))";
        assertEquals("(E (T (T " + f1 + ") * (F id)))", t.sexp());
        t.validate(g);
    }

    @Test
    void errorsReportIndexAndStateExpectations() {
        ParseException e = assertThrows(ParseException.class, () -> slr.parse(Fx.toks("id id")));
        assertEquals(1, e.index());
        assertEquals(List.of("$", ")", "*", "+"), List.copyOf(e.expected()));
        ParseException end = assertThrows(ParseException.class, () -> slr.parse(Fx.toks("id +")));
        assertEquals(2, end.index());
        assertEquals(Set.of("(", "id"), end.expected());
        ParseException empty = assertThrows(ParseException.class, () -> slr.parse(List.of()));
        assertEquals(0, empty.index());
        ParseException open = assertThrows(ParseException.class, () -> slr.parse(Fx.toks("( id")));
        assertEquals(2, open.index());
        // detected after reducing to E in the state "( E . )": only ")" and "+" can follow
        assertEquals(Set.of(")", "+"), open.expected());
    }

    @Test
    void literalEndMarkerInInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> slr.parse(List.of("id", "$")));
    }

    @Test
    void classicNonSlrGrammarHasOneShiftReduceConflictOnEquals() {
        Slr s = Slr.build(Grammar.parse(Fx.LVAL));
        assertEquals(10, s.stateCount()); // dragon book 4.49: I0..I9
        assertEquals(1, s.conflicts().size());
        Slr.Conflict c = s.conflicts().get(0);
        assertEquals("=", c.terminal());
        assertEquals("shift/reduce", c.kind());
        assertFalse(s.isSlr1());
        // shift wins in the table: the conflicted state shifts "="
        assertEquals('S', s.action(c.state(), "=").kind());
        assertThrows(IllegalStateException.class, () -> s.parse(Fx.toks("id = id")));
    }

    @Test
    void llOneGrammarCanBeReduceReduceForSlr() {
        Slr s = Slr.build(Grammar.parse(Fx.LLNOTSLR));
        assertEquals(List.of(new Slr.Conflict(0, "a", "reduce/reduce"), new Slr.Conflict(0, "b", "reduce/reduce")),
                s.conflicts());
    }

    @Test
    void danglingElseAndAmbiguousGrammarsAreShiftReduce() {
        Slr e = Slr.build(Grammar.parse(Fx.ELSE));
        assertEquals(1, e.conflicts().size());
        assertEquals("else", e.conflicts().get(0).terminal());
        assertEquals("shift/reduce", e.conflicts().get(0).kind());
        Slr a = Slr.build(Grammar.parse(Fx.AMB));
        assertEquals(1, a.conflicts().size());
        assertEquals("+", a.conflicts().get(0).terminal());
    }

    @Test
    void epsilonProductionsReduceWithoutConsuming() {
        Slr s = Slr.build(Grammar.parse("S -> ( S ) S | eps\n"));
        assertTrue(s.isSlr1());
        assertEquals("(S)", s.parse(List.of()).sexp());
        Tree t = s.parse(Fx.toks("( ) ( )"));
        assertEquals("(S ( (S) ) (S ( (S) ) (S)))", t.sexp());
        assertEquals(Fx.toks("( ) ( )"), t.yield());
        ParseException x = assertThrows(ParseException.class, () -> s.parse(Fx.toks("( ) )")));
        assertEquals(2, x.index());
    }

    @Test
    void duplicateAlternativesDoNotCrashTheBuilder() {
        // two identical productions give an unavoidable reduce/reduce on $; the table keeps the lower id
        Slr s = Slr.build(Grammar.parse("S -> a | a\n"));
        assertEquals(List.of(new Slr.Conflict(s.go(0, "a"), "$", "reduce/reduce")), s.conflicts());
        assertEquals(new Slr.Action('R', 0), s.action(s.go(0, "a"), "$"));
    }
}
