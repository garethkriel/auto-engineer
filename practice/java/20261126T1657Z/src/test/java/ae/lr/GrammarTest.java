package ae.lr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GrammarTest {

    static final String EXPR = """
        # dragon book grammar 4.1
        E -> E + T | T
        T -> T * F | F
        F -> ( E ) | id
        """;

    @Test
    void productionsAreNumberedFromOneAfterAugmentedZero() {
        Grammar g = Grammar.parse(EXPR);
        assertEquals(7, g.productions().size());
        assertEquals("$accept", g.productions().get(0).lhs());
        assertEquals(List.of("E"), g.productions().get(0).rhs());
        assertEquals("E", g.productions().get(1).lhs());
        assertEquals(List.of("E", "+", "T"), g.productions().get(1).rhs());
        assertEquals(List.of("(", "E", ")"), g.productions().get(5).rhs());
        assertEquals(List.of("id"), g.productions().get(6).rhs());
        assertEquals("E", g.start());
    }

    @Test
    void terminalsAreSymbolsNeverOnALeftSide() {
        Grammar g = Grammar.parse(EXPR);
        assertEquals(Set.of("E", "T", "F"), g.nonterminals());
        assertEquals(Set.of("+", "*", "(", ")", "id"), g.terminals());
    }

    @Test
    void firstSetsMatchTheTextbook() {
        Grammar g = Grammar.parse(EXPR);
        assertEquals(Set.of("(", "id"), g.first("E"));
        assertEquals(Set.of("(", "id"), g.first("T"));
        assertEquals(Set.of("(", "id"), g.first("F"));
        assertEquals(Set.of("+"), g.first("+"));
        assertFalse(g.nullable("E"));
    }

    @Test
    void followSetsMatchTheTextbook() {
        Grammar g = Grammar.parse(EXPR);
        assertEquals(Set.of("$", ")", "+"), g.follow("E"));
        assertEquals(Set.of("$", ")", "+", "*"), g.follow("T"));
        assertEquals(Set.of("$", ")", "+", "*"), g.follow("F"));
    }

    @Test
    void nullableChainsAndEpsilonFirstFollow() {
        // S -> A B c ; A -> a | ε ; B -> b | ε
        Grammar g = Grammar.parse("""
            S -> A B c
            A -> a | ε
            B -> b | %empty
            """);
        assertTrue(g.nullable("A"));
        assertTrue(g.nullable("B"));
        assertFalse(g.nullable("S"));
        assertEquals(Set.of("a", "b", "c"), g.first("S"));
        assertEquals(Set.of("b", "c"), g.follow("A"));   // FIRST(B c) = {b, c}
        assertEquals(Set.of("c"), g.follow("B"));
        assertEquals(Set.of("$"), g.follow("S"));
        assertEquals(List.of(), g.productions().get(3).rhs());
    }

    @Test
    void followPropagatesThroughNullableSuffix() {
        // FOLLOW(A) gets FOLLOW(S) because B is nullable and ends the production.
        Grammar g = Grammar.parse("""
            S -> A B | d
            A -> a
            B -> ε | b
            """);
        assertEquals(Set.of("b", "$"), g.follow("A"));
        assertEquals(Set.of("$"), g.follow("B"));
    }

    @Test
    void productiveAndReachable() {
        Grammar g = Grammar.parse("""
            S -> a | U
            U -> U u
            X -> x
            """);
        assertEquals(Set.of("S", "X"), g.productive());
        assertEquals(Set.of("S", "U"), g.reachable());
    }

    @Test
    void rejectsMalformedGrammars() {
        assertThrows(IllegalArgumentException.class, () -> Grammar.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Grammar.parse("# only a comment\n"));
        assertThrows(IllegalArgumentException.class, () -> Grammar.parse("S a b"));
        assertThrows(IllegalArgumentException.class, () -> Grammar.parse("S -> a |"));
        assertThrows(IllegalArgumentException.class, () -> Grammar.parse("S -> a | | b"));
        assertThrows(IllegalArgumentException.class, () -> Grammar.parse("S -> ε a"));
        assertThrows(IllegalArgumentException.class, () -> Grammar.parse("S -> a $ b"));
        assertThrows(IllegalArgumentException.class, () -> Grammar.parse("$accept -> a"));
        assertThrows(IllegalArgumentException.class, () -> Grammar.parse("S T -> a"));
        assertThrows(IllegalArgumentException.class, () -> Grammar.parse(" -> a"));
    }

    @Test
    void controlGrammarWithTheSameShapeLoads() {
        // each rejected input above differs from this one by one defect
        Grammar g = Grammar.parse("S -> a | b\nT -> ε\n");
        assertEquals(3, g.productions().size() - 1);
    }

    @Test
    void errorMessageNamesTheLine() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> Grammar.parse("S -> a\n\nT b\n"));
        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    }
}
