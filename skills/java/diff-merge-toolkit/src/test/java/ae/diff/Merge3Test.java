package ae.diff;

import static ae.diff.TestSupport.lines;
import static ae.diff.TestSupport.mutate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Merge3Test {

    private static Merge3.Result m(String base, String ours, String theirs) {
        return Merge3.merge(lines(base), lines(ours), lines(theirs));
    }

    @Test
    void disjointEditsMergeCleanly() {
        Merge3.Result r = m("a b c d e", "a B c d e", "a b c D e");
        assertTrue(r.isClean());
        assertEquals(lines("a B c D e"), r.lines());
    }

    @Test
    void oneSideUnchangedTakesTheOther() {
        assertEquals(lines("a X c"), m("a b c", "a b c", "a X c").lines());
        assertEquals(lines("a X c"), m("a b c", "a X c", "a b c").lines());
        assertEquals(lines("a b c"), m("a b c", "a b c", "a b c").lines());
    }

    @Test
    void identicalChangesOnBothSidesAreNotAConflict() {
        Merge3.Result r = m("a b c", "a X c", "a X c");
        assertTrue(r.isClean());
        assertEquals(lines("a X c"), r.lines());
        Merge3.Result ins = m("a c", "a Z c", "a Z c");
        assertTrue(ins.isClean());
        assertEquals(lines("a Z c"), ins.lines());
    }

    @Test
    void differentChangesToTheSameLineConflict() {
        Merge3.Result r = m("a b c", "a X c", "a Y c");
        assertFalse(r.isClean());
        assertEquals(1, r.conflictCount());
        Merge3.Conflict c = (Merge3.Conflict) r.chunks().stream()
                .filter(k -> k instanceof Merge3.Conflict).findFirst().orElseThrow();
        assertEquals(lines("b"), c.base());
        assertEquals(lines("X"), c.ours());
        assertEquals(lines("Y"), c.theirs());
        assertEquals(String.join("\n", "a", "<<<<<<< mine", "X", "=======", "Y", ">>>>>>> yours", "c", ""),
                r.render("mine", "yours"));
        assertThrows(IllegalStateException.class, r::lines);
    }

    @Test
    void differentInsertionsAtTheSamePointConflictButOrderIsKept() {
        Merge3.Result r = m("a c", "a X c", "a Y c");
        assertEquals(1, r.conflictCount());
        Merge3.Conflict c = (Merge3.Conflict) r.chunks().get(1);
        assertEquals(List.of(), c.base());
        assertEquals(lines("X"), c.ours());
        assertEquals(lines("Y"), c.theirs());
    }

    @Test
    void deleteVersusModifyConflicts() {
        Merge3.Result r = m("a b c", "a c", "a B c");
        assertEquals(1, r.conflictCount());
        Merge3.Conflict c = (Merge3.Conflict) r.chunks().stream()
                .filter(k -> k instanceof Merge3.Conflict).findFirst().orElseThrow();
        assertEquals(List.of(), c.ours());
        assertEquals(lines("B"), c.theirs());
    }

    @Test
    void touchingButNonOverlappingRangesMergeCleanly() {
        // ours replaces b, theirs replaces c: base ranges [1,2) and [2,3) touch but do not intersect.
        Merge3.Result r = m("a b c d", "a B c d", "a b C d");
        assertTrue(r.isClean());
        assertEquals(lines("a B C d"), r.lines());
        // insertion exactly at the end of the other side's replaced range
        Merge3.Result ins = m("a b c", "a B c", "a b Z c");
        assertTrue(ins.isClean(), "insert at index 2 touches replacement of [1,2)");
        assertEquals(lines("a B Z c"), ins.lines());
    }

    @Test
    void insertionInsideAnotherSidesDeletedRangeConflicts() {
        Merge3.Result r = m("a b c d", "a d", "a b X c d");
        assertEquals(1, r.conflictCount());
    }

    @Test
    void chainedOverlapsFormOneConflictGroup() {
        // ours changes [1,3), theirs changes [2,4) and [4,5)? keep [4,5) separate: theirs second edit is touching, not overlapping.
        Merge3.Result r = m("a b c d e f", "a X Y d e f", "a b P Q e f");
        assertEquals(1, r.conflictCount());
        Merge3.Conflict c = (Merge3.Conflict) r.chunks().stream()
                .filter(k -> k instanceof Merge3.Conflict).findFirst().orElseThrow();
        assertEquals(lines("b c d"), c.base());
        assertEquals(lines("X Y d"), c.ours());
        assertEquals(lines("b P Q"), c.theirs());
    }

    @Test
    void emptyInputs() {
        assertTrue(m("", "", "").isClean());
        assertEquals(lines("x y"), m("", "x y", "x y").lines());
        assertEquals(1, m("", "x", "y").conflictCount());
        assertEquals(List.of(), m("a b", "", "a b").lines());
    }

    @Test
    void chunksTileTheBaseInOrder() {
        Random r = new Random(5);
        for (int i = 0; i < 400; i++) {
            List<String> base = TestSupport.randomLines(r, 25, 5);
            List<String> ours = mutate(r, base, 5);
            List<String> theirs = mutate(r, base, 5);
            int pos = 0;
            for (Merge3.Chunk ch : Merge3.merge(base, ours, theirs).chunks()) {
                assertEquals(pos, ch.baseStart(), "chunks must be contiguous");
                assertTrue(ch.baseEnd() >= ch.baseStart());
                pos = ch.baseEnd();
            }
            assertEquals(base.size(), pos);
        }
    }

    @Test
    void degenerateCasesAndSymmetryOnRandomInputs() {
        Random r = new Random(17);
        int clean = 0;
        int conflicted = 0;
        for (int i = 0; i < 1500; i++) {
            List<String> base = TestSupport.randomLines(r, 20, 6);
            List<String> ours = mutate(r, base, 6);
            List<String> theirs = mutate(r, base, 6);
            String ctx = "base=" + base + " ours=" + ours + " theirs=" + theirs;
            assertEquals(theirs, Merge3.merge(base, base, theirs).lines(), ctx);
            assertEquals(ours, Merge3.merge(base, ours, base).lines(), ctx);
            assertEquals(ours, Merge3.merge(base, ours, ours).lines(), ctx);
            Merge3.Result ab = Merge3.merge(base, ours, theirs);
            Merge3.Result ba = Merge3.merge(base, theirs, ours);
            assertEquals(ab.isClean(), ba.isClean(), ctx);
            assertEquals(ab.conflictCount(), ba.conflictCount(), ctx);
            if (ab.isClean()) {
                assertEquals(ab.lines(), ba.lines(), ctx);
                clean++;
            } else {
                conflicted++;
                List<Merge3.Chunk> x = ab.chunks();
                List<Merge3.Chunk> y = ba.chunks();
                for (int k = 0; k < x.size(); k++) {
                    if (x.get(k) instanceof Merge3.Conflict cx) {
                        Merge3.Conflict cy = (Merge3.Conflict) y.get(k);
                        assertEquals(cx.ours(), cy.theirs(), ctx);
                        assertEquals(cx.theirs(), cy.ours(), ctx);
                        assertEquals(cx.base(), cy.base(), ctx);
                    }
                }
            }
        }
        assertTrue(clean > 300 && conflicted > 150, "outcome mix clean=" + clean + " conflicted=" + conflicted);
    }

    /** Unique base lines, edits placed at separated sites; each site is edited by exactly one side. */
    @Test
    void separatedEditsMatchTheCompositionOracle() {
        Random r = new Random(29);
        for (int i = 0; i < 400; i++) {
            int sites = 1 + r.nextInt(6);
            List<String> base = new ArrayList<>();
            List<String> ours = new ArrayList<>();
            List<String> theirs = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            int id = 0;
            for (int s = 0; s < sites; s++) {
                String keep = "keep" + id++;
                base.add(keep);
                ours.add(keep);
                theirs.add(keep);
                expected.add(keep);
                int kind = r.nextInt(3);
                boolean oursSide = r.nextBoolean();
                List<String> editor = oursSide ? ours : theirs;
                List<String> other = oursSide ? theirs : ours;
                String old = "old" + id++;
                String neu = "new" + id++;
                switch (kind) {
                    case 0 -> { // replace
                        base.add(old);
                        other.add(old);
                        editor.add(neu);
                        expected.add(neu);
                    }
                    case 1 -> { // delete
                        base.add(old);
                        other.add(old);
                    }
                    default -> { // insert
                        editor.add(neu);
                        expected.add(neu);
                    }
                }
            }
            String keep = "keepEnd";
            base.add(keep);
            ours.add(keep);
            theirs.add(keep);
            expected.add(keep);
            Merge3.Result res = Merge3.merge(base, ours, theirs);
            String ctx = "base=" + base + " ours=" + ours + " theirs=" + theirs;
            assertTrue(res.isClean(), ctx);
            assertEquals(expected, res.lines(), ctx);
        }
    }

    @Test
    void renderWritesCleanLinesAndMarkersInOrder() {
        Merge3.Result r = m("a b c d e", "a X c Q e", "a Y c d e");
        assertEquals(String.join("\n", "a", "<<<<<<< ours", "X", "=======", "Y", ">>>>>>> theirs",
                "c", "Q", "e", ""), r.render("ours", "theirs"));
        assertEquals("a\nB\n", Merge3.merge(lines("a b"), lines("a B"), lines("a b")).render("o", "t"));
        assertEquals("", Merge3.merge(List.of(), List.of(), List.of()).render("o", "t"));
    }

    @Test
    void gitMergeFileAgreesOnSeparatedEditsWithUniqueLines(@TempDir Path dir) throws Exception {
        assumeTrue(runOk(dir, "git", "--version"), "git unavailable");
        Random r = new Random(41);
        int cleanCases = 0;
        int conflictCases = 0;
        for (int i = 0; i < 60; i++) {
            int n = 8 + r.nextInt(10);
            List<String> base = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                base.add("u" + k);
            }
            List<String> ours = new ArrayList<>(base);
            List<String> theirs = new ArrayList<>(base);
            // edit only even indices => separated by at least one kept line
            for (int k = 0; k < n; k += 2) {
                int what = r.nextInt(5);
                if (what == 1) {
                    ours.set(k, "O" + k);
                } else if (what == 2) {
                    theirs.set(k, "T" + k);
                } else if (what == 3) {
                    ours.set(k, "O" + k);
                    theirs.set(k, "T" + k);
                } else if (what == 4) {
                    ours.set(k, "S" + k);
                    theirs.set(k, "S" + k);
                }
            }
            Files.writeString(dir.resolve("base"), join(base));
            Files.writeString(dir.resolve("ours"), join(ours));
            Files.writeString(dir.resolve("theirs"), join(theirs));
            Process pr = new ProcessBuilder("git", "merge-file", "-p", "ours", "base", "theirs")
                    .directory(dir.toFile()).redirectErrorStream(false).start();
            String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            pr.getErrorStream().readAllBytes();
            assertTrue(pr.waitFor(30, TimeUnit.SECONDS));
            int gitConflicts = pr.exitValue();
            Merge3.Result mine = Merge3.merge(base, ours, theirs);
            String ctx = "base=" + base + " ours=" + ours + " theirs=" + theirs;
            // git joins conflicts separated by few lines, so only compare conflict presence, not the count
            assertEquals(gitConflicts > 0, mine.conflictCount() > 0, ctx);
            if (gitConflicts == 0) {
                assertEquals(out, join(mine.lines()), ctx);
                cleanCases++;
            } else {
                conflictCases++;
            }
        }
        assertTrue(cleanCases > 5 && conflictCases > 5, "mix clean=" + cleanCases + " conflict=" + conflictCases);
    }

    private static boolean runOk(Path dir, String... cmd) {
        try {
            Process pr = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            pr.getInputStream().readAllBytes();
            return pr.waitFor(10, TimeUnit.SECONDS) && pr.exitValue() == 0;
        } catch (java.io.IOException | InterruptedException e) {
            return false;
        }
    }

    private static String join(List<String> ls) {
        return ls.isEmpty() ? "" : String.join("\n", ls) + "\n";
    }
}
