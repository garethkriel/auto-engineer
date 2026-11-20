package ae.peg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrammarAnalysisTest {
    private static String error(String text) {
        return assertThrows(GrammarException.class, () -> Grammar.parse(text), text).getMessage();
    }

    @Test
    void undefinedRuleNamesTheReferrer() {
        assertEquals("undefined rule 'B' referenced in rule 'A'", error("A <- 'x' (B)"));
        assertEquals("undefined rule 'Q' referenced in rule 'C'", error("A <- 'x'\nC <- !Q"));
    }

    @Test
    void directLeftRecursion() {
        assertEquals("left recursion: E -> E", error("E <- E '+' 'n' / 'n'"));
    }

    @Test
    void indirectLeftRecursionShowsCycle() {
        assertEquals("left recursion: A -> B -> C -> A", error("A <- B 'x'\nB <- C\nC <- A / 'c'"));
    }

    @Test
    void leftRecursionThroughNullablePrefix() {
        assertEquals("left recursion: A -> A", error("A <- 'p'? A 'x' / 'y'"));
        assertEquals("left recursion: A -> N -> A", error("A <- N 'x' / 'y'\nN <- 'k'* A"));
    }

    @Test
    void leftRecursionThroughPredicate() {
        assertEquals("left recursion: A -> A", error("A <- !A 'x'"));
    }

    @Test
    void rightRecursionAndRecursionAfterConsumingIsFine() {
        Grammar g = Grammar.parse("A <- 'a' A / 'b'\nB <- 'a'+ B?");
        assertFalse(g.isNullable("A"));
    }

    @Test
    void emptyLoopBodiesAreRejected() {
        assertEquals("loop body can match the empty string in rule 'A'", error("A <- ('a'?)*"));
        assertEquals("loop body can match the empty string in rule 'A'", error("A <- (!'a')+"));
        assertEquals("loop body can match the empty string in rule 'B'", error("A <- 'x'*\nB <- A+"));
    }

    @Test
    void nullabilityIsAFixpointOverRules() {
        Grammar g = Grammar.parse("""
                A <- B C
                B <- 'x'?
                C <- D / 'c'
                D <- &'q'
                E <- 'x'+ B
                F <- ''
                G <- .
                """);
        assertTrue(g.isNullable("A"));
        assertTrue(g.isNullable("B"));
        assertTrue(g.isNullable("C"));
        assertTrue(g.isNullable("D"));
        assertFalse(g.isNullable("E"));
        assertTrue(g.isNullable("F"));
        assertFalse(g.isNullable("G"));
        assertThrows(IllegalArgumentException.class, () -> g.isNullable("Z"));
    }
}
