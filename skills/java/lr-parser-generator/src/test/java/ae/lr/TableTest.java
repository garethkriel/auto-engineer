package ae.lr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TableTest {

    static final String CC = "S -> C C\nC -> c C | d\n";
    static final String LR_NOT_SLR = """
        S -> L = R | R
        L -> * R | id
        R -> L
        """;
    static final String LR1_NOT_LALR = """
        S -> a E a | b E b | a F b | b F a
        E -> e
        F -> e
        """;
    static final String DANGLING = "S -> i S | i S e S | a\n";
    static final String AMBIG = "E -> E + E | id\n";

    static LrTable table(String g, Mode m) {
        return LrTable.build(Grammar.parse(g), m);
    }

    static Set<String> symbols(LrTable t) {
        return t.conflicts().stream().map(Conflict::symbol).collect(Collectors.toSet());
    }

    @Test
    void expressionGrammarStateCountsMatchDragonBook() {
        // LR(0)/SLR/LALR: 12 states; canonical LR(1): 22 states (Aho et al., grammar 4.1)
        assertEquals(12, table(GrammarTest.EXPR, Mode.SLR).stateCount());
        assertEquals(12, table(GrammarTest.EXPR, Mode.LALR).stateCount());
        assertEquals(22, table(GrammarTest.EXPR, Mode.LR1).stateCount());
        for (Mode m : Mode.values()) {
            assertEquals(List.of(), table(GrammarTest.EXPR, m).conflicts(), m.toString());
        }
    }

    @Test
    void ccGrammarStateCounts() {
        assertEquals(7, table(CC, Mode.SLR).stateCount());
        assertEquals(7, table(CC, Mode.LALR).stateCount());
        assertEquals(10, table(CC, Mode.LR1).stateCount());
    }

    @Test
    void ccGrammarFirstStateActionsAreLiteral() {
        // symbol order S, C, c, d => goto targets are numbered 1, 2, 3, 4
        LrTable t = table(CC, Mode.LALR);
        assertEquals(List.of(new Action.Shift(3)), t.actions(0, "c"));
        assertEquals(List.of(new Action.Shift(4)), t.actions(0, "d"));
        assertEquals(1, t.goTo(0, "S").orElseThrow());
        assertEquals(2, t.goTo(0, "C").orElseThrow());
        assertEquals(List.of(new Action.Accept()), t.actions(1, "$"));
        // state 4 = [C -> d .]; production 3 is C -> d
        assertEquals(List.of(new Action.Reduce(3)), t.actions(4, "c"));
        assertEquals(List.of(new Action.Reduce(3)), t.actions(4, "d"));
        // LALR merged this state with "d" after a "c", whose lookahead is $: union {c, d, $}
        assertEquals(List.of(new Action.Reduce(3)), t.actions(4, "$"));
        assertEquals(Set.of("c", "d"), t.expected(0));
        assertEquals(Set.of("c", "d", "$"), t.expected(4));
    }

    @Test
    void slrReducesOnFollowWhereLalrUsesTrueLookahead() {
        // canonical LR(1): the state after the first "d" reduces only on {c, d}; FOLLOW(C) adds $
        assertEquals(List.of(), table(CC, Mode.LR1).actions(4, "$"));
        assertEquals(Set.of("c", "d"), table(CC, Mode.LR1).expected(4));
        assertEquals(List.of(new Action.Reduce(3)), table(CC, Mode.SLR).actions(4, "$"));
    }

    @Test
    void dragonBookGrammarIsNotSlrButIsLalr() {
        LrTable slr = table(LR_NOT_SLR, Mode.SLR);
        assertEquals(1, slr.conflicts().size());
        Conflict c = slr.conflicts().get(0);
        assertEquals("=", c.symbol());
        assertEquals(Conflict.Kind.SHIFT_REDUCE, c.kind());
        assertEquals(2, c.actions().size());
        assertTrue(c.actions().get(0) instanceof Action.Shift);
        assertEquals(new Action.Reduce(5), c.actions().get(1)); // 5: R -> L
        String text = slr.describe(c);
        assertTrue(text.contains("R -> L ."), text);
        assertTrue(text.contains("S -> L . = R"), text);

        assertEquals(10, table(LR_NOT_SLR, Mode.LALR).stateCount());
        assertEquals(List.of(), table(LR_NOT_SLR, Mode.LALR).conflicts());
        assertEquals(List.of(), table(LR_NOT_SLR, Mode.LR1).conflicts());
        assertTrue(table(LR_NOT_SLR, Mode.LR1).stateCount() > 10);
    }

    @Test
    void lr1GrammarThatIsNotLalrHasReduceReduceConflicts() {
        LrTable lalr = table(LR1_NOT_LALR, Mode.LALR);
        assertEquals(2, lalr.conflicts().size());
        assertEquals(Set.of("a", "b"), symbols(lalr));
        for (Conflict c : lalr.conflicts()) {
            assertEquals(Conflict.Kind.REDUCE_REDUCE, c.kind());
            assertEquals(List.of(new Action.Reduce(5), new Action.Reduce(6)), c.actions()); // E->e, F->e
        }
        // merging the two "e" states is exactly what loses the information
        assertEquals(2, table(LR1_NOT_LALR, Mode.SLR).conflicts().size());
        assertEquals(List.of(), table(LR1_NOT_LALR, Mode.LR1).conflicts());
        assertEquals(lalr.stateCount() + 1, table(LR1_NOT_LALR, Mode.LR1).stateCount());
    }

    @Test
    void danglingElseIsAShiftReduceConflictOnElse() {
        for (Mode m : new Mode[] {Mode.SLR, Mode.LALR}) {
            LrTable t = table(DANGLING, m);
            assertEquals(1, t.conflicts().size(), m.toString());
            assertEquals("e", t.conflicts().get(0).symbol());
            assertEquals(Conflict.Kind.SHIFT_REDUCE, t.conflicts().get(0).kind());
        }
        LrTable lr1 = table(DANGLING, Mode.LR1);
        assertTrue(lr1.conflicts().size() >= 1);
        for (Conflict c : lr1.conflicts()) {
            assertEquals("e", c.symbol());
        }
    }

    @Test
    void ambiguousExpressionGrammarConflictsOnPlus() {
        for (Mode m : Mode.values()) {
            LrTable t = table(AMBIG, m);
            assertEquals(1, t.conflicts().size(), m.toString());
            Conflict c = t.conflicts().get(0);
            assertEquals("+", c.symbol());
            assertEquals(Conflict.Kind.SHIFT_REDUCE, c.kind());
            String text = t.describe(c);
            assertTrue(text.contains("E -> E + E ."), text);
            assertTrue(text.contains("E -> E . + E"), text);
        }
    }

    @Test
    void conflictsAreOrderedByStateThenSymbol() {
        LrTable t = table(LR1_NOT_LALR, Mode.LALR);
        List<Conflict> cs = t.conflicts();
        assertEquals(cs.get(0).state(), cs.get(1).state());
        assertEquals("a", cs.get(0).symbol());
        assertEquals("b", cs.get(1).symbol());
    }

    @Test
    void epsilonProductionsReduceOnLookahead() {
        // S -> A b ; A -> ε | a : state 0 must reduce A -> ε on b and shift a
        LrTable t = table("S -> A b\nA -> ε | a\n", Mode.LALR);
        assertEquals(List.of(), t.conflicts());
        assertEquals(List.of(new Action.Reduce(2)), t.actions(0, "b"));
        assertEquals(1, t.actions(0, "a").size());
        assertTrue(t.actions(0, "a").get(0) instanceof Action.Shift);
        assertEquals(List.of(), t.actions(0, "$"));
    }

    @Test
    void sharedCoreMergesLookaheadsInLalr() {
        // after "( id" vs "id" in the expression grammar the same core is reached with
        // different lookaheads; LALR must reduce F -> id on the union {+ * ) $}.
        LrTable t = table(GrammarTest.EXPR, Mode.LALR);
        int s = ((Action.Shift) t.actions(0, "id").get(0)).state();
        for (String la : List.of("+", "*", ")", "$")) {
            assertEquals(List.of(new Action.Reduce(6)), t.actions(s, la), la); // 6: F -> id
        }
        assertEquals(Set.of("+", "*", ")", "$"), t.expected(s));
    }

    @Test
    void lr1KeepsLookaheadsApartAfterTheOpenParen() {
        // In canonical LR(1) the "id" reached from state 0 reduces only on {+ * $}: ")" is not valid yet.
        LrTable t = table(GrammarTest.EXPR, Mode.LR1);
        int s = ((Action.Shift) t.actions(0, "id").get(0)).state();
        assertEquals(Set.of("+", "*", "$"), t.expected(s));
        assertEquals(List.of(), t.actions(s, ")"));
    }

    @Test
    void goToOfMissingEntryIsEmpty() {
        LrTable t = table(CC, Mode.LALR);
        assertTrue(t.goTo(1, "C").isEmpty());
    }
}
