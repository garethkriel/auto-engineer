package ae.rope;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The invariant checker must itself be able to fail: feed it hand-built corrupt trees directly. */
class RopeCheckerTest {

    private static Rope.Leaf leaf(String s) {
        return new Rope.Leaf(s);
    }

    @Test
    void acceptsAValidHandBuiltTree() {
        Rope ok = new Rope(new Rope.Branch(new Rope.Branch(leaf("ab"), leaf("c\n")), leaf("d")));
        ok.check();
        assertEquals("abc\nd", ok.toString());
        assertEquals(2, ok.lineCount());
    }

    @Test
    void rejectsUnbalancedTree() {
        Rope.Node chain = new Rope.Branch(leaf("a"), new Rope.Branch(leaf("b"), new Rope.Branch(leaf("c"), leaf("d"))));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new Rope(chain).check());
        assertTrue(e.getMessage().startsWith("unbalanced"), e.getMessage());
    }

    @Test
    void rejectsOversizeLeaf() {
        Rope big = new Rope(leaf("x".repeat(Rope.LEAF_MAX + 1)));
        IllegalStateException e = assertThrows(IllegalStateException.class, big::check);
        assertTrue(e.getMessage().startsWith("leaf too long"), e.getMessage());
    }

    @Test
    void rejectsEmptyNonRootLeaf() {
        Rope r = new Rope(new Rope.Branch(leaf(""), leaf("a")));
        IllegalStateException e = assertThrows(IllegalStateException.class, r::check);
        assertTrue(e.getMessage().startsWith("empty non-root leaf"), e.getMessage());
        new Rope(leaf("")).check(); // the empty root leaf is legal
    }
}
