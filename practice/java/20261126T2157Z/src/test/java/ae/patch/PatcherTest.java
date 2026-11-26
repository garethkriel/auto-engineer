package ae.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PatcherTest {
    private static final String BASE = "a b c d e f g h i j k l m n o p";

    private static Patch patchFor(String from, String to, int ctx) {
        return UnifiedDiff.parse(UnifiedDiff.format(Lines.of(from), Lines.of(to), ctx, "x", "y"));
    }

    @Test
    void appliesCleanly() {
        Patch p = patchFor(BASE, "a b c d E f g h i j k l m n o p", 2);
        Patcher.Result r = Patcher.apply(Lines.of(BASE), p, 10);
        assertTrue(r.ok());
        assertEquals(Lines.of("a b c d E f g h i j k l m n o p"), r.lines());
        assertEquals(List.of(0), r.offsets());
    }

    @Test
    void findsHunkAtPositiveOffset() {
        Patch p = patchFor(BASE, "a b c d E f g h i j k l m n o p", 2);
        List<String> shifted = Lines.of("x1 x2 " + BASE);
        Patcher.Result r = Patcher.apply(shifted, p, 10);
        assertTrue(r.ok());
        assertEquals(Lines.of("x1 x2 a b c d E f g h i j k l m n o p"), r.lines());
        assertEquals(List.of(2), r.offsets());
    }

    @Test
    void findsHunkAtNegativeOffset() {
        Patch p = patchFor(BASE, "a b c d e f g h i j k l m n O p", 2);
        // target is the base without its first 4 lines; the context n o p is still present
        Patcher.Result r = Patcher.apply(Lines.of("e f g h i j k l m n o p"), p, 10);
        assertTrue(r.ok(), "shifted by -4");
        assertEquals(List.of(-4), r.offsets());
        assertEquals(Lines.of("e f g h i j k l m n O p"), r.lines());
    }

    @Test
    void offsetBeyondMaxIsRejected() {
        Patch p = patchFor(BASE, "a b c d E f g h i j k l m n o p", 2);
        Patcher.Result r = Patcher.apply(Lines.of("x1 x2 x3 " + BASE), p, 2);
        assertFalse(r.ok());
        assertEquals(List.of(0), r.rejected());
        assertNull(r.offsets().get(0));
        assertEquals(Lines.of("x1 x2 x3 " + BASE), r.lines(), "a rejected hunk leaves the text untouched");
        assertTrue(Patcher.apply(Lines.of("x1 x2 x3 " + BASE), p, 3).ok(), "control: offset 3 allowed");
    }

    @Test
    void rejectsOnlyTheHunkWhoseContextChanged() {
        Patch p = patchFor(BASE, "a B c d e f g h i j k l m n O p", 1);
        assertEquals(2, p.hunks().size());
        List<String> target = Lines.of("a b c d e f g h i j k l m n o p".replace("n o p", "n Z p"));
        Patcher.Result r = Patcher.apply(target, p, 5);
        assertFalse(r.ok());
        assertEquals(List.of(1), r.rejected());
        assertEquals(Lines.of("a B c d e f g h i j k l m n Z p"), r.lines());
    }

    @Test
    void earlierOffsetCarriesToLaterHunks() {
        Patch p = patchFor(BASE, "a B c d e f g h i j k l m n O p", 1);
        Patcher.Result r = Patcher.apply(Lines.of("z " + BASE), p, 3);
        assertTrue(r.ok());
        assertEquals(List.of(1, 1), r.offsets());
    }

    @Test
    void deletedLineMustMatchExactly() {
        Patch p = patchFor("a b c", "a c", 1);
        assertFalse(Patcher.apply(Lines.of("a X c"), p, 5).ok(), "context ok but deleted line differs");
        assertTrue(Patcher.apply(Lines.of("a b c"), p, 5).ok());
    }

    @Test
    void pureInsertionUsesHeaderPosition() {
        Patch p = patchFor("a b c", "a b X c", 0);
        Patcher.Result r = Patcher.apply(Lines.of("a b c"), p, 3);
        assertEquals(Lines.of("a b X c"), r.lines());
        Patch top = patchFor("a b", "N a b", 0);
        assertEquals(Lines.of("N a b"), Patcher.apply(Lines.of("a b"), top, 0).lines());
        Patch end = patchFor("a b", "a b Z", 0);
        assertEquals(Lines.of("a b Z"), Patcher.apply(Lines.of("a b"), end, 0).lines());
    }

    @Test
    void reverseUndoesPatch() {
        Patch p = patchFor(BASE, "a B c d e f g h i j k l m n O q", 2);
        List<String> forward = Patcher.apply(Lines.of(BASE), p, 0).lines();
        Patcher.Result back = Patcher.apply(forward, p.reverse(), 0);
        assertTrue(back.ok());
        assertEquals(Lines.of(BASE), back.lines());
        assertEquals(p, p.reverse().reverse());
    }

    @Test
    void emptyPatchIsIdentity() {
        Patcher.Result r = Patcher.apply(Lines.of("a b"), new Patch("x", "y", List.of()), 0);
        assertTrue(r.ok());
        assertEquals(Lines.of("a b"), r.lines());
    }

    @Test
    void hunksDoNotOverlapAfterOffsetSearch() {
        // two identical blocks; the second hunk must not reuse lines consumed by the first
        Patch p = patchFor("a b c a b c", "a B c a b C", 1);
        Patcher.Result r = Patcher.apply(Lines.of("a b c a b c"), p, 5);
        assertTrue(r.ok());
        assertEquals(Lines.of("a B c a b C"), r.lines());
    }

    @Test
    void hunkNeverReusesLinesConsumedByAnEarlierHunk() {
        // hunk 1 rewrites x at index 4; hunk 2 expects x at index 6, where there is none:
        // the only x within reach (index 4, offset -2) is already consumed
        Patch p = UnifiedDiff.parse(
            "--- a\n+++ b\n@@ -5 +5 @@\n-x\n+X\n@@ -7 +7 @@\n-x\n+Y\n");
        Patcher.Result r = Patcher.apply(Lines.of("i0 i1 i2 i3 x p q r"), p, 3);
        assertEquals(List.of(1), r.rejected());
        assertEquals(Lines.of("i0 i1 i2 i3 X p q r"), r.lines());
    }

    @Test
    void searchNeverReadsPastTheEndOfTheText() {
        // old lines [b c] expected at index 1, but the target ends after b: a probe at index 1 would
        // run one line past the end; it must be skipped, not throw
        Patch p = patchFor("a b c", "a b C", 2);
        Patcher.Result r = Patcher.apply(Lines.of("a b"), p, 3);
        assertEquals(List.of(0), r.rejected());
        assertEquals(Lines.of("a b"), r.lines());
    }
}
