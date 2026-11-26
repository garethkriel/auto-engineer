package ae.earley;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TreeTest {

    static ParseResult parse(String grammar, String input) {
        return new EarleyParser(Grammar.parse(grammar)).parse(input);
    }

    static List<String> shown(ParseResult r, int limit) {
        return r.trees(limit).stream().map(Tree::toString).toList();
    }

    static final String AMBIG = "E -> E '+' E | 'n'";

    @Test
    void unambiguousTreeLiteral() {
        ParseResult r = parse("S -> A 'b'\nA -> 'a' A | 'a'", "aab");
        assertEquals(Optional.of(BigInteger.ONE), r.treeCount());
        assertEquals(List.of("(S (A 'a' (A 'a')) 'b')"), shown(r, 10));
    }

    @Test
    void ambiguousSumHasTwoTreesInDocumentedOrder() {
        ParseResult r = parse(AMBIG, "n+n+n");
        assertEquals(Optional.of(BigInteger.TWO), r.treeCount());
        // Rule 0 (E -> E + E): first child span ends earliest first, so the left operand 'n' comes first.
        assertEquals(List.of(
            "(E (E 'n') '+' (E (E 'n') '+' (E 'n')))",
            "(E (E (E 'n') '+' (E 'n')) '+' (E 'n'))"), shown(r, 10));
    }

    @Test
    void catalanNumbersForSums() {
        long[] catalan = {1, 1, 2, 5, 14, 42, 132, 429, 1430};
        for (int pluses = 0; pluses < catalan.length; pluses++) {
            String input = "n" + "+n".repeat(pluses);
            ParseResult r = parse(AMBIG, input);
            assertEquals(Optional.of(BigInteger.valueOf(catalan[pluses])), r.treeCount(), "pluses=" + pluses);
        }
    }

    @Test
    void catalanHugeCountsStayExact() {
        // C(30) = 3814986502092304 ; 31 operands = 30 pluses. Count only, never enumerate.
        ParseResult r = parse(AMBIG, "n" + "+n".repeat(30));
        assertEquals(Optional.of(new BigInteger("3814986502092304")), r.treeCount());
    }

    @Test
    void danglingElseHasTwoReadingsUntilDisambiguated() {
        String g = "S -> 'i' S | 'i' S 'e' S | 'x'";
        ParseResult r = parse(g, "iixex");
        assertEquals(Optional.of(BigInteger.TWO), r.treeCount());
        assertEquals(List.of(
            "(S 'i' (S 'i' (S 'x') 'e' (S 'x')))",
            "(S 'i' (S 'i' (S 'x')) 'e' (S 'x'))"), shown(r, 5));
        // Matched/unmatched split removes the ambiguity.
        String fixed = "S -> M | U\nM -> 'i' M 'e' M | 'x'\nU -> 'i' S | 'i' M 'e' U";
        assertEquals(Optional.of(BigInteger.ONE), parse(fixed, "iixex").treeCount());
    }

    @Test
    void hiddenNullableAmbiguity() {
        String g = "S -> A A\nA -> 'a' |";
        assertEquals(Optional.of(BigInteger.ONE), parse(g, "").treeCount());
        assertEquals(Optional.of(BigInteger.TWO), parse(g, "a").treeCount());
        assertEquals(Optional.of(BigInteger.ONE), parse(g, "aa").treeCount());
        assertEquals(List.of("(S (A) (A 'a'))", "(S (A 'a') (A))"), shown(parse(g, "a"), 10));
    }

    @Test
    void rejectedInputHasNoTrees() {
        ParseResult r = parse(AMBIG, "n+");
        assertEquals(Optional.of(BigInteger.ZERO), r.treeCount());
        assertEquals(List.of(), r.trees(10));
    }

    @Test
    void limitCapsEnumerationAndZeroGivesEmpty() {
        ParseResult r = parse(AMBIG, "n+n+n+n+n"); // C(4) = 14
        assertEquals(Optional.of(BigInteger.valueOf(14)), r.treeCount());
        assertEquals(14, r.trees(100).size());
        assertEquals(5, r.trees(5).size());
        assertEquals(List.of(), r.trees(0));
        assertThrows(IllegalArgumentException.class, () -> r.trees(-1));
        assertEquals(14, r.trees(100).stream().map(Tree::toString).distinct().count());
        // the capped list is a prefix of the full list
        assertEquals(shown(r, 100).subList(0, 5), shown(r, 5));
    }

    @Test
    void yieldAndEmptyNodeRendering() {
        ParseResult r = parse("S -> A 'b' A\nA ->", "b");
        Tree t = r.trees(1).get(0);
        assertEquals("(S (A) 'b' (A))", t.toString());
        assertEquals("b", t.yield());
        assertEquals("", ((Tree.Node) t.children().get(0)).yield());
    }

    @Test
    void nodeCarriesRuleIndexAndSymbol() {
        ParseResult r = parse("S -> 'a' | 'b' S", "bba");
        Tree.Node root = (Tree.Node) r.trees(1).get(0);
        assertEquals("S", root.symbol());
        assertEquals(1, root.rule());
        Tree.Node inner = (Tree.Node) root.children().get(1);
        assertEquals(1, inner.rule());
        Tree.Node last = (Tree.Node) inner.children().get(1);
        assertEquals(0, last.rule());
        assertEquals(new Tree.Leaf('a'), last.children().get(0));
    }

    @Test
    void cyclicGrammarHasInfinitelyManyTreesButFiniteEnumeration() {
        String g = "S -> S | 'a'";
        ParseResult r = parse(g, "a");
        assertTrue(r.accepted());
        assertEquals(Optional.empty(), r.treeCount());
        assertEquals(List.of("(S 'a')"), shown(r, 10)); // (S (S 'a')) repeats S over the same span: excluded
    }

    @Test
    void cycleThroughNullablesIsInfinite() {
        String g = "S -> A S B | 'x'\nA ->\nB ->";
        ParseResult r = parse(g, "x");
        assertEquals(Optional.empty(), r.treeCount());
        assertEquals(List.of("(S 'x')"), shown(r, 10));
    }

    @Test
    void cyclicRuleOnlyMattersWhenItIsUsedInASuccessfulParse() {
        // T is cyclic but unreachable from any accepted derivation of "a"; count stays finite.
        String g = "S -> 'a' | 'b' T\nT -> T | 'c'";
        assertEquals(Optional.of(BigInteger.ONE), parse(g, "a").treeCount());
        assertEquals(Optional.empty(), parse(g, "bc").treeCount());
    }

    @Test
    void emptyInputWithNullableCycle() {
        ParseResult r = parse("A -> A A |", "");
        assertEquals(Optional.empty(), r.treeCount());
        assertEquals(List.of("(A)"), shown(r, 10));
    }

    @Test
    void parseTreesForNestedParenthesesLiteral() {
        ParseResult r = parse("S -> '(' S ')' S |", "(())");
        assertEquals(List.of("(S '(' (S '(' (S) ')' (S)) ')' (S))"), shown(r, 5));
    }
}
