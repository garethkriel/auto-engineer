package ae.parsegen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LL1Test {
    private final Grammar g = Grammar.parse(Fx.LL);
    private final LL1 ll = LL1.build(g);

    @Test
    void tableCellsMatchHandDerivation() {
        // ids: 0 E->T E1, 1 E1->+ T E1, 2 E1->eps, 3 T->F T1, 4 T1->* F T1, 5 T1->eps, 6 F->( E ), 7 F->id
        assertTrue(ll.conflicts().isEmpty());
        assertTrue(ll.isLL1());
        assertEquals(13, ll.size()); // E:2 E1:3 T:2 T1:4 F:2
        assertEquals(OptionalInt.of(0), ll.entry("E", "("));
        assertEquals(OptionalInt.of(0), ll.entry("E", "id"));
        assertEquals(OptionalInt.of(1), ll.entry("E1", "+"));
        assertEquals(OptionalInt.of(2), ll.entry("E1", ")"));
        assertEquals(OptionalInt.of(2), ll.entry("E1", "$"));
        assertEquals(OptionalInt.of(3), ll.entry("T", "("));
        assertEquals(OptionalInt.of(3), ll.entry("T", "id"));
        assertEquals(OptionalInt.of(4), ll.entry("T1", "*"));
        assertEquals(OptionalInt.of(5), ll.entry("T1", "+"));
        assertEquals(OptionalInt.of(5), ll.entry("T1", ")"));
        assertEquals(OptionalInt.of(5), ll.entry("T1", "$"));
        assertEquals(OptionalInt.of(6), ll.entry("F", "("));
        assertEquals(OptionalInt.of(7), ll.entry("F", "id"));
        assertEquals(OptionalInt.empty(), ll.entry("F", "+"));
        assertEquals(OptionalInt.empty(), ll.entry("E1", "*"));
    }

    @Test
    void parsesPrecedenceIntoTree() {
        Tree t = ll.parse(Fx.toks("id + id * id"));
        assertEquals("(E (T (F id) (T1)) (E1 + (T (F id) (T1 * (F id) (T1))) (E1)))", t.sexp());
        assertEquals(Fx.toks("id + id * id"), t.yield());
        assertEquals("E", t.symbol());
        assertEquals(0, t.production());
        t.validate(g);
    }

    @Test
    void parsesParenthesesAndSingleToken() {
        assertEquals("(E (T (F id) (T1)) (E1))", ll.parse(Fx.toks("id")).sexp());
        Tree t = ll.parse(Fx.toks("( id )"));
        assertEquals("(E (T (F ( (E (T (F id) (T1)) (E1)) )) (T1)) (E1))", t.sexp());
        t.validate(g);
    }

    @Test
    void errorAtEndOfInput() {
        ParseException e = assertThrows(ParseException.class, () -> ll.parse(Fx.toks("id +")));
        assertEquals(2, e.index());
        assertEquals(Set.of("(", "id"), e.expected());
    }

    @Test
    void errorInTheMiddleListsRowOfTheNonterminalOnTop() {
        ParseException e = assertThrows(ParseException.class, () -> ll.parse(Fx.toks("id id")));
        assertEquals(1, e.index());
        assertEquals(List.of("$", ")", "*", "+"), List.copyOf(e.expected()));
    }

    @Test
    void errorOnTerminalMismatchNamesThatTerminal() {
        ParseException e = assertThrows(ParseException.class, () -> ll.parse(Fx.toks("( id *")));
        assertEquals(3, e.index());
        assertEquals(Set.of("(", "id"), e.expected());
        ParseException f = assertThrows(ParseException.class, () -> ll.parse(Fx.toks("( id + id")));
        assertEquals(4, f.index());
        // T1 and E1 already expanded to eps on $, so the terminal ")" on top is what is missing
        assertEquals(Set.of(")"), f.expected());
    }

    @Test
    void emptyAndUnknownTokens() {
        ParseException e = assertThrows(ParseException.class, () -> ll.parse(List.of()));
        assertEquals(0, e.index());
        ParseException u = assertThrows(ParseException.class, () -> ll.parse(Fx.toks("id ? id")));
        assertEquals(1, u.index());
    }

    @Test
    void literalEndMarkerInInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ll.parse(List.of("id", "$")));
    }

    @Test
    void leftRecursionConflictsOnBothFirstTokens() {
        LL1 bad = LL1.build(Grammar.parse(Fx.LR));
        assertFalse(bad.isLL1());
        assertTrue(bad.conflicts().contains(new LL1.Conflict("E", "(", List.of(0, 1))));
        assertTrue(bad.conflicts().contains(new LL1.Conflict("E", "id", List.of(0, 1))));
        assertTrue(bad.conflicts().contains(new LL1.Conflict("T", "id", List.of(2, 3))));
        assertEquals(4, bad.conflicts().size()); // E and T each clash on "(" and "id"; F is fine
    }

    @Test
    void conflictingGrammarRefusesToParse() {
        LL1 bad = LL1.build(Grammar.parse(Fx.ELSE));
        assertEquals(List.of(new LL1.Conflict("S", "if", List.of(0, 1))), bad.conflicts());
        assertThrows(IllegalStateException.class, () -> bad.parse(Fx.toks("a")));
    }

    @Test
    void commonPrefixIsAConflictButFactoredFormIsNot() {
        LL1 bad = LL1.build(Grammar.parse("A -> a A | a\n"));
        assertEquals(List.of(new LL1.Conflict("A", "a", List.of(0, 1))), bad.conflicts());
        LL1 good = LL1.build(Grammar.parse("A -> a B\nB -> A | eps\n"));
        assertTrue(good.isLL1());
        assertEquals("(A a (B (A a (B))))", good.parse(Fx.toks("a a")).sexp());
    }

    @Test
    void nullableAlternativeConflictsWithFollow() {
        // A -> a | eps and FOLLOW(A) contains a: the eps production competes with production 0 on a
        LL1 bad = LL1.build(Grammar.parse("S -> A a\nA -> a | eps\n"));
        assertEquals(List.of(new LL1.Conflict("A", "a", List.of(1, 2))), bad.conflicts());
    }

    @Test
    void llOneButNotSlrGrammarParses() {
        LL1 t = LL1.build(Grammar.parse(Fx.LLNOTSLR));
        assertTrue(t.isLL1());
        assertEquals("(S (A) a (A) b)", t.parse(Fx.toks("a b")).sexp());
        assertEquals("(S (B) b (B) a)", t.parse(Fx.toks("b a")).sexp());
        assertThrows(ParseException.class, () -> t.parse(Fx.toks("a a")));
    }
}
