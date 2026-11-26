package ae.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffTest {
    private static final String A = "a b c d e f g h i j";

    @Test
    void equalTextsGiveEmptyOutput() {
        assertEquals("", UnifiedDiff.format(Lines.of(A), Lines.of(A), 3, "x", "y"));
    }

    @Test
    void twoSeparateHunksWithContextOne() {
        // d->D at index 3, i deleted at index 8; gap of 4 keeps (e f g h) > 2*1 so two hunks
        String expected = String.join("\n",
            "--- x", "+++ y",
            "@@ -3,3 +3,3 @@", " c", "-d", "+D", " e",
            "@@ -8,3 +8,2 @@", " h", "-i", " j", "");
        assertEquals(expected, UnifiedDiff.format(Lines.of(A), Lines.of("a b c D e f g h j"), 1, "x", "y"));
    }

    @Test
    void hunksMergeWhenGapIsAtMostTwiceContext() {
        // gap of exactly 2 keeps (c d)... changes at 1 and 4 -> keeps c,d between = 2 <= 2*1: one hunk
        String expected = String.join("\n",
            "--- x", "+++ y",
            "@@ -1,6 +1,6 @@", " a", "-b", "+B", " c", " d", "-e", "+E", " f", "");
        assertEquals(expected,
            UnifiedDiff.format(Lines.of("a b c d e f"), Lines.of("a B c d E f"), 1, "x", "y"));
        // gap of 3 keeps (c d e) > 2 -> two hunks
        String two = UnifiedDiff.format(Lines.of("a b c d e f g"), Lines.of("a B c d e F g"), 1, "x", "y");
        assertEquals(2, two.lines().filter(l -> l.startsWith("@@")).count());
    }

    @Test
    void insertIntoEmptyAndDeleteEverything() {
        assertEquals("--- x\n+++ y\n@@ -0,0 +1,2 @@\n+p\n+q\n",
            UnifiedDiff.format(List.of(), Lines.of("p q"), 3, "x", "y"));
        assertEquals("--- x\n+++ y\n@@ -1 +0,0 @@\n-p\n",
            UnifiedDiff.format(Lines.of("p"), List.of(), 3, "x", "y"));
    }

    @Test
    void countOfOneIsOmittedInHeader() {
        assertEquals("--- x\n+++ y\n@@ -1 +1 @@\n-p\n+q\n",
            UnifiedDiff.format(Lines.of("p"), Lines.of("q"), 3, "x", "y"));
    }

    @Test
    void parseRoundTripsFormat() {
        String text = UnifiedDiff.format(Lines.of(A), Lines.of("a b c D e f g h j"), 2, "old", "new");
        Patch p = UnifiedDiff.parse(text);
        assertEquals("old", p.oldLabel());
        assertEquals("new", p.newLabel());
        assertEquals(1, p.hunks().size());
        assertEquals(UnifiedDiff.render(p), text);
    }

    @Test
    void hunkSidesAndIndex() {
        Patch p = UnifiedDiff.parse("--- x\n+++ y\n@@ -0,0 +1,2 @@\n+p\n+q\n");
        Hunk h = p.hunks().get(0);
        assertEquals(0, h.oldIndex());
        assertEquals(List.of(), h.oldLines());
        assertEquals(List.of("p", "q"), h.newLines());
        Hunk g = UnifiedDiff.parse("--- x\n+++ y\n@@ -4,2 +4,2 @@\n a\n-b\n+B\n").hunks().get(0);
        assertEquals(3, g.oldIndex());
    }

    // ---- strict parser: each case differs from the valid control by one defect ----

    private static final String OK = "--- x\n+++ y\n@@ -1,2 +1,2 @@\n a\n-b\n+B\n";

    @Test
    void controlParses() {
        assertEquals(1, UnifiedDiff.parse(OK).hunks().size());
    }

    private static String message(String text) {
        return assertThrows(PatchFormatException.class, () -> UnifiedDiff.parse(text)).getMessage();
    }

    @Test
    void rejectsCountMismatches() {
        assertTrue(message(OK.replace("-1,2 +1,2", "-1,3 +1,2")).contains("line 3"));
        assertTrue(message(OK.replace("-1,2 +1,2", "-1,2 +1,3")).contains("line 3"));
        String extra = message(OK + " extra\n");
        assertTrue(extra.contains("line 7") && extra.contains("extra body line"), extra);
    }

    @Test
    void rejectsBadBodyLine() {
        assertTrue(message(OK.replace(" a\n", "a\n")).contains("line 4"));
        assertTrue(message(OK.replace(" a\n", "\n")).contains("line 4"));
    }

    @Test
    void rejectsMissingOrBadHeaders() {
        assertTrue(message(OK.replace("--- x\n", "")).contains("line 1"));
        assertTrue(message(OK.replace("+++ y", "++ y")).contains("line 2"));
        assertTrue(message(OK.replace("@@ -1,2 +1,2 @@", "@@ -1,2 +1,2")).contains("line 3"));
        assertTrue(message(OK.replace("@@ -1,2 +1,2 @@", "@@ -a,2 +1,2 @@")).contains("line 3"));
    }

    @Test
    void rejectsNoNewlineMarker() {
        assertTrue(message(OK + "\\ No newline at end of file\n").contains("unsupported"));
    }

    @Test
    void rejectsMissingTrailingNewline() {
        assertTrue(message(OK.substring(0, OK.length() - 1)).contains("newline"));
    }

    @Test
    void rejectsOverlappingOrDescendingHunks() {
        String two = "--- x\n+++ y\n@@ -5,1 +5,1 @@\n-a\n+b\n@@ -2,1 +2,1 @@\n-c\n+d\n";
        assertTrue(message(two).contains("line 6"));
        String overlap = "--- x\n+++ y\n@@ -2,2 +2,2 @@\n a\n-b\n+c\n@@ -3,1 +3,1 @@\n-b\n+c\n";
        assertTrue(message(overlap).contains("line 7"));
        String fine = "--- x\n+++ y\n@@ -2,2 +2,2 @@\n a\n-b\n+c\n@@ -4,1 +4,1 @@\n-b\n+c\n";
        assertEquals(2, UnifiedDiff.parse(fine).hunks().size());
    }

    @Test
    void rejectsEmptyPatchBodyAfterHeaders() {
        // headers only is a valid empty patch (control), garbage after headers is not
        assertEquals(0, UnifiedDiff.parse("--- x\n+++ y\n").hunks().size());
        assertTrue(message("--- x\n+++ y\nhello\n").contains("line 3"));
    }
}
