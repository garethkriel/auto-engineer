package ae.parsegen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnalysisTest {
    private final Analysis a = new Analysis(Grammar.parse(Fx.LL));

    @Test
    void nullableSymbols() {
        assertEquals(Set.of("E1", "T1"), a.nullable());
        assertTrue(a.isNullable("E1"));
        assertFalse(a.isNullable("E"));
        assertFalse(a.isNullable("id"));
    }

    @Test
    void firstSetsMatchDragonBook() {
        assertEquals(Set.of("(", "id"), a.first("E"));
        assertEquals(Set.of("(", "id"), a.first("T"));
        assertEquals(Set.of("(", "id"), a.first("F"));
        assertEquals(Set.of("+"), a.first("E1"));
        assertEquals(Set.of("*"), a.first("T1"));
        assertEquals(Set.of("id"), a.first("id"));
    }

    @Test
    void followSetsMatchDragonBook() {
        assertEquals(Set.of(")", "$"), a.follow("E"));
        assertEquals(Set.of(")", "$"), a.follow("E1"));
        assertEquals(Set.of("+", ")", "$"), a.follow("T"));
        assertEquals(Set.of("+", ")", "$"), a.follow("T1"));
        assertEquals(Set.of("+", "*", ")", "$"), a.follow("F"));
    }

    @Test
    void sequenceFirstSkipsNullablePrefixOnly() {
        assertEquals(Set.of("*", "+"), a.firstSeq(List.of("T1", "E1")));
        assertTrue(a.nullableSeq(List.of("T1", "E1")));
        assertEquals(Set.of("*", "id"), a.firstSeq(List.of("T1", "id", "E1")));
        assertFalse(a.nullableSeq(List.of("T1", "id")));
        assertEquals(Set.of(), a.firstSeq(List.of()));
        assertTrue(a.nullableSeq(List.of()));
    }

    @Test
    void chainedNullablesPropagate() {
        Analysis b = new Analysis(Grammar.parse("S -> A B c\nA -> a | eps\nB -> b | A\n"));
        assertEquals(Set.of("A", "B"), b.nullable());
        assertEquals(Set.of("a", "b", "c"), b.first("S"));
        // FOLLOW(A) = FIRST(B c) = {a, b, c} because B can start with a via B -> A
        assertEquals(Set.of("a", "b", "c"), b.follow("A"));
        assertEquals(Set.of("c"), b.follow("B"));
        assertEquals(Set.of("$"), b.follow("S"));
    }

    @Test
    void followUsesTrailingNullableSuffix() {
        // FOLLOW(S) = {$}; X can end S via the nullable Y, so FOLLOW(Y) and FOLLOW(X) both contain $
        Analysis b = new Analysis(Grammar.parse("S -> X Y\nX -> x\nY -> y | eps\n"));
        assertEquals(Set.of("y", "$"), b.follow("X"));
        assertEquals(Set.of("$"), b.follow("Y"));
    }

    @Test
    void followOfStartAlwaysContainsEndEvenWhenRecursive() {
        Analysis b = new Analysis(Grammar.parse("S -> ( S ) S | eps\n"));
        assertEquals(Set.of(")", "$"), b.follow("S"));
    }
}
