package ae.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class Merge3Test {
    private static final String BASE = "a b c d e";

    private static List<Merge3.Chunk> m(String ours, String theirs) {
        return Merge3.merge(Lines.of(BASE), Lines.of(ours), Lines.of(theirs));
    }

    private static String render(List<Merge3.Chunk> chunks) {
        return Merge3.render(chunks, "ours", "base", "theirs");
    }

    @Test
    void disjointEditsMergeCleanly() {
        List<Merge3.Chunk> r = m("a B c d e", "a b c D e");
        assertFalse(Merge3.hasConflicts(r));
        assertEquals("a\nB\nc\nD\ne\n", render(r));
    }

    @Test
    void oneSideOnly() {
        assertEquals("a\nB\nc\nd\ne\n", render(m("a B c d e", BASE)));
        assertEquals("a\nB\nc\nd\ne\n", render(m(BASE, "a B c d e")));
    }

    @Test
    void sameEditOnBothSidesIsClean() {
        List<Merge3.Chunk> r = m("a X c d e", "a X c d e");
        assertFalse(Merge3.hasConflicts(r));
        assertEquals("a\nX\nc\nd\ne\n", render(r));
    }

    @Test
    void differentEditsOfSameLineConflict() {
        assertEquals("a\n<<<<<<< ours\nB\n||||||| base\nb\n=======\nX\n>>>>>>> theirs\nc\nd\ne\n",
            render(m("a B c d e", "a X c d e")));
    }

    @Test
    void touchingEditsConflictOverUnionOfSpans() {
        // ours replaces b, theirs replaces c: adjacent -> one conflict over b c
        assertEquals("a\n<<<<<<< ours\nB\nc\n||||||| base\nb\nc\n=======\nb\nC\n>>>>>>> theirs\nd\ne\n",
            render(m("a B c d e", "a b C d e")));
    }

    @Test
    void editsSeparatedByOneKeptLineDoNotConflict() {
        assertFalse(Merge3.hasConflicts(m("a B c d e", "a b c D e")));
    }

    @Test
    void deleteVersusInsertAtBoundaryConflicts() {
        assertEquals("a\n<<<<<<< ours\n||||||| base\nb\n=======\nb\nX\n>>>>>>> theirs\nc\nd\ne\n",
            render(m("a c d e", "a b X c d e")));
    }

    @Test
    void insertionsAtSamePointConflictUnlessEqual() {
        assertTrue(Merge3.hasConflicts(m("a x b c d e", "a y b c d e")));
        assertFalse(Merge3.hasConflicts(m("a x b c d e", "a x b c d e")));
    }

    @Test
    void chainedClusterJoinsThreeRegions() {
        // ours: b and d replaced; theirs: c replaced -> theirs touches both
        String out = render(m("a B c D e", "a b C d e"));
        assertEquals("a\n<<<<<<< ours\nB\nc\nD\n||||||| base\nb\nc\nd\n=======\nb\nC\nd\n>>>>>>> theirs\ne\n", out);
    }

    @Test
    void endAndStartInsertions() {
        assertEquals("N\na\nb\nc\nd\ne\nZ\n", render(m("N a b c d e", "a b c d e Z")));
    }

    @Test
    void identitiesOnLiterals() {
        assertEquals(Lines.text(Lines.of("q w e")),
            render(Merge3.merge(Lines.of(BASE), Lines.of("q w e"), Lines.of(BASE))));
        assertEquals("", render(Merge3.merge(List.of(), List.of(), List.of())));
    }

    @Test
    void stableChunksAreCoalesced() {
        List<Merge3.Chunk> r = m("a B c d e", "a b c D e");
        assertEquals(1, r.size());
    }

    @Test
    void swappingSidesSwapsConflictSections() {
        List<Merge3.Chunk> swapped = Merge3.merge(Lines.of(BASE), Lines.of("a X c d e"), Lines.of("a B c d e"));
        assertEquals("a\n<<<<<<< ours\nX\n||||||| base\nb\n=======\nB\n>>>>>>> theirs\nc\nd\ne\n",
            render(swapped));
    }
}
