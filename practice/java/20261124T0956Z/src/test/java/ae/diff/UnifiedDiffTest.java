package ae.diff;

import static ae.diff.TestSupport.lines;
import static ae.diff.TestSupport.mutate;
import static ae.diff.TestSupport.randomLines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnifiedDiffTest {

    private static List<String> numbered(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add("line" + i);
        }
        return out;
    }

    @Test
    void formatsExactTextForOneHunk() {
        List<String> a = numbered(10);
        List<String> b = new ArrayList<>(a);
        b.set(4, "CHANGED");
        String expected = String.join("\n",
                "--- old.txt",
                "+++ new.txt",
                "@@ -3,5 +3,5 @@",
                " line3",
                " line4",
                "-line5",
                "+CHANGED",
                " line6",
                " line7",
                "");
        assertEquals(expected, UnifiedDiff.format("old.txt", "new.txt", a, b, 2));
    }

    @Test
    void identicalInputsProduceNoHunks() {
        assertEquals("--- a\n+++ b\n", UnifiedDiff.format("a", "b", numbered(4), numbered(4), 3));
    }

    @Test
    void emptyRangesUseTheLineBeforeConvention() {
        assertEquals("--- a\n+++ b\n@@ -0,0 +1,2 @@\n+x\n+y\n",
                UnifiedDiff.format("a", "b", List.of(), lines("x y"), 3));
        assertEquals("--- a\n+++ b\n@@ -1,2 +0,0 @@\n-x\n-y\n",
                UnifiedDiff.format("a", "b", lines("x y"), List.of(), 3));
        // pure insertion in the middle with no context: old range empty, start = line before
        List<String> a = lines("a b c");
        List<String> b = lines("a b Z c");
        assertEquals("--- a\n+++ b\n@@ -2,0 +3 @@\n+Z\n", UnifiedDiff.format("a", "b", a, b, 0));
    }

    @Test
    void gapOfTwiceTheContextMergesAndOneMoreSplits() {
        for (int ctx = 0; ctx <= 3; ctx++) {
            for (int gap = 2 * ctx - 1; gap <= 2 * ctx + 2; gap++) {
                if (gap < 1) {
                    continue;
                }
                List<String> a = numbered(40);
                List<String> b = new ArrayList<>(a);
                b.set(10, "X");
                b.set(10 + gap + 1, "Y");
                int hunks = UnifiedDiff.parse(UnifiedDiff.format("a", "b", a, b, ctx)).hunks().size();
                assertEquals(gap <= 2 * ctx ? 1 : 2, hunks, "ctx=" + ctx + " gap=" + gap);
            }
        }
    }

    @Test
    void contextIsClampedAtFileEdges() {
        List<String> a = numbered(6);
        List<String> b = new ArrayList<>(a);
        b.set(0, "FIRST");
        b.set(5, "LAST");
        Patch p = UnifiedDiff.parse(UnifiedDiff.format("a", "b", a, b, 3));
        assertEquals(1, p.hunks().size());
        Patch.Hunk h = p.hunks().get(0);
        assertEquals(1, h.oldStart());
        assertEquals(6, h.oldLen());
        assertEquals(1, h.newStart());
        assertEquals(6, h.newLen());
    }

    @Test
    void roundTripFormatParseApplyOnRandomInputs() {
        Random r = new Random(3);
        int hunkTotal = 0;
        for (int i = 0; i < 400; i++) {
            List<String> a = randomLines(r, 40, 6);
            List<String> b = mutate(r, a, 6);
            for (int ctx = 0; ctx <= 4; ctx++) {
                String text = UnifiedDiff.format("a", "b", a, b, ctx);
                Patch p = UnifiedDiff.parse(text);
                String msg = "ctx=" + ctx + " a=" + a + " b=" + b + "\n" + text;
                assertEquals(b, p.apply(a), msg);
                assertEquals(a, p.reverse().apply(b), msg);
                hunkTotal += p.hunks().size();
            }
        }
        assertTrue(hunkTotal > 500, "generator must produce hunks, got " + hunkTotal);
    }

    @Test
    void parseKeepsNamesStripsTimestampsAndIgnoresNoNewlineMarker() {
        String text = "--- old.txt\t2020-01-01 00:00:00\n+++ new.txt\t2020-01-02\n"
                + "@@ -1,2 +1,2 @@ context heading\n a\n-b\n\\ No newline at end of file\n+c\n";
        Patch p = UnifiedDiff.parse(text);
        assertEquals("old.txt", p.oldName());
        assertEquals("new.txt", p.newName());
        assertEquals(List.of("a", "c"), p.apply(lines("a b")));
    }

    @Test
    void parseRejectsMalformedPatches() {
        String head = "--- a\n+++ b\n";
        assertThrows(PatchException.class, () -> UnifiedDiff.parse("+++ b\n@@ -1 +1 @@\n-x\n+y\n"));
        assertThrows(PatchException.class, () -> UnifiedDiff.parse(head + "@@ -1,2 +1,1 @@\n-x\n+y\n"),
                "counts promise two old lines but only one is present");
        assertThrows(PatchException.class, () -> UnifiedDiff.parse(head + "@@ -1,1 +1,1 @@\n-x\n+y\n+z\n"),
                "extra line beyond declared counts");
        assertThrows(PatchException.class, () -> UnifiedDiff.parse(head + "@@ -1,1 +1,2 @@\n-x\n c\n+d\n"),
                "context line overshoots the declared old length");
        assertThrows(PatchException.class, () -> UnifiedDiff.parse(head + "@@ -1,1 +1,1 @@\n?x\n"));
        assertThrows(PatchException.class, () -> UnifiedDiff.parse(head + "@@ nonsense @@\n-x\n"));
        assertThrows(PatchException.class, () -> UnifiedDiff.parse(head + "stray\n@@ -1 +1 @@\n-x\n+y\n"));
    }

    @Test
    void applyIsStrict() {
        Patch p = UnifiedDiff.parse("--- a\n+++ b\n@@ -2,3 +2,3 @@\n b\n-c\n+C\n d\n");
        assertEquals(lines("a b C d e"), p.apply(lines("a b c d e")));
        assertThrows(PatchException.class, () -> p.apply(lines("a b X d e")), "deleted line differs");
        assertThrows(PatchException.class, () -> p.apply(lines("a Q c d e")), "context differs");
        assertThrows(PatchException.class, () -> p.apply(lines("a b c")), "context runs past the end");
        assertThrows(PatchException.class, () -> p.apply(lines("a")), "hunk starts beyond the file");
    }

    @Test
    void applyRejectsOverlappingOutOfOrderAndInconsistentHunks() {
        Patch overlap = UnifiedDiff.parse(
                "--- a\n+++ b\n@@ -1,2 +1,2 @@\n a\n-b\n+B\n@@ -2,2 +2,2 @@\n b\n-c\n+C\n");
        assertThrows(PatchException.class, () -> overlap.apply(lines("a b c")));
        Patch order = UnifiedDiff.parse(
                "--- a\n+++ b\n@@ -4,1 +4,1 @@\n-d\n+D\n@@ -1,1 +1,1 @@\n-a\n+A\n");
        assertThrows(PatchException.class, () -> order.apply(lines("a b c d")));
        Patch badNew = UnifiedDiff.parse("--- a\n+++ b\n@@ -1,1 +5,1 @@\n-a\n+A\n");
        assertThrows(PatchException.class, () -> badNew.apply(lines("a")), "new start must match output position");
    }

    @Test
    void applyRejectsHandBuiltHunkWhoseCountsDisagreeWithItsLines() {
        Patch lying = new Patch("a", "b", List.of(new Patch.Hunk(1, 2, 1, 1,
                List.of(new Patch.Line('-', "a"), new Patch.Line('+', "A")))));
        PatchException e = assertThrows(PatchException.class, () -> lying.apply(lines("a b")));
        assertTrue(e.getMessage().contains("counts"), e.getMessage());
    }

    @Test
    void appliesTheSamePatchToTheSameInputTwiceWithoutMutatingIt() {
        List<String> a = numbered(8);
        List<String> b = new ArrayList<>(a);
        b.remove(3);
        Patch p = UnifiedDiff.parse(UnifiedDiff.format("a", "b", a, b, 1));
        List<String> copy = new ArrayList<>(a);
        assertEquals(b, p.apply(a));
        assertEquals(b, p.apply(a));
        assertEquals(copy, a);
    }

    private static boolean tool(String... cmd) {
        try {
            Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            pr.getInputStream().readAllBytes();
            return pr.waitFor(10, TimeUnit.SECONDS) && pr.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String run(Path dir, String... cmd) throws IOException, InterruptedException {
        Process pr = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        pr.waitFor(30, TimeUnit.SECONDS);
        return out;
    }

    private static String join(List<String> ls) {
        return ls.isEmpty() ? "" : String.join("\n", ls) + "\n";
    }

    @Test
    void gnuPatchAcceptsOurOutputAndWeAcceptDiffUOutput(@TempDir Path dir) throws Exception {
        assumeTrue(tool("patch", "--version") && tool("diff", "--version"), "GNU patch/diff unavailable");
        Random r = new Random(21);
        int checked = 0;
        for (int i = 0; i < 40; i++) {
            List<String> a = randomLines(r, 30, 8);
            List<String> b = mutate(r, a, 8);
            if (a.equals(b) || a.isEmpty() || b.isEmpty()) {
                continue;
            }
            Files.writeString(dir.resolve("f.txt"), join(a));
            Files.writeString(dir.resolve("new.txt"), join(b));
            int ctx = r.nextInt(4);
            Files.writeString(dir.resolve("p.diff"), UnifiedDiff.format("f.txt", "f.txt", a, b, ctx));
            run(dir, "patch", "-s", "-p0", "-i", "p.diff");
            assertEquals(join(b), Files.readString(dir.resolve("f.txt")), "GNU patch on our diff, ctx=" + ctx);

            String theirs = run(dir, "diff", "-U" + ctx, "new.txt", "new.txt");
            assertEquals("", theirs);
            Files.writeString(dir.resolve("f.txt"), join(a));
            String real = run(dir, "diff", "-U" + ctx, "f.txt", "new.txt");
            Patch p = UnifiedDiff.parse(real);
            assertEquals(b, p.apply(a), "our parser on diff -U" + ctx + " output:\n" + real);
            checked++;
        }
        assertTrue(checked >= 25, "too few comparable cases: " + checked);
    }
}
