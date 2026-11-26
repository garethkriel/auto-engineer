package ae.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Differential tests against GNU diff/patch/diff3. Generators use fresh unique lines for every
 * edit, so the minimal edit script (and the LCS) is unique and tool tie-breaking cannot matter.
 */
class GnuDifferentialTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void toolsPresent() {
        assumeTrue(has("diff") && has("patch") && has("diff3"), "GNU diffutils/patch not installed");
    }

    private static boolean has(String tool) {
        try {
            return run(null, List.of(tool, "--version")).code == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private record Out(int code, String stdout) { }

    private static Out run(Path cwd, List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("timeout: " + cmd);
        }
        return new Out(p.exitValue(), out);
    }

    private Path write(String name, List<String> lines) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, Lines.text(lines));
        return f;
    }

    private int fresh;

    private String freshLine() {
        return "n" + (fresh++);
    }

    private List<String> baseFile(Random rnd, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("b" + i);
        }
        return out;
    }

    /** Applies random unique-line edits (replace / insert / delete) at random places. */
    private List<String> edit(Random rnd, List<String> src, int edits) {
        List<String> out = new ArrayList<>(src);
        for (int e = 0; e < edits; e++) {
            int kind = rnd.nextInt(3);
            if (out.isEmpty()) {
                kind = 1;
            }
            int at = out.isEmpty() ? 0 : rnd.nextInt(out.size());
            if (kind == 0) {
                out.set(at, freshLine());
            } else if (kind == 1) {
                out.add(at, freshLine());
            } else {
                out.remove(at);
            }
        }
        return out;
    }

    @Test
    void formatMatchesGnuDiffU() throws Exception {
        Random rnd = new Random(11);
        int nonEmpty = 0;
        for (int round = 0; round < 120; round++) {
            List<String> a = baseFile(rnd, rnd.nextInt(40));
            List<String> b = edit(rnd, a, rnd.nextInt(8));
            int ctx = rnd.nextInt(5);
            Path fa = write("a", a);
            Path fb = write("b", b);
            Out gnu = run(dir, List.of("diff", "-U" + ctx, "--label", "L1", "--label", "L2",
                fa.toString(), fb.toString()));
            String mine = UnifiedDiff.format(a, b, ctx, "L1", "L2");
            assertEquals(gnu.stdout, mine, "round " + round + " ctx " + ctx + " a=" + a + " b=" + b);
            if (!mine.isEmpty()) {
                nonEmpty++;
            }
        }
        assertTrue(nonEmpty >= 80, "generator produced only " + nonEmpty + " non-empty diffs");
    }

    @Test
    void patchAppliesLikeGnuPatchIncludingOffsets() throws Exception {
        Random rnd = new Random(23);
        int shifted = 0;
        for (int round = 0; round < 80; round++) {
            int n = 40 + rnd.nextInt(30);
            List<String> base = baseFile(rnd, n);
            // patch edits sit at multiples of 12 (+4), target-only edits at +10: >= 6 lines apart
            List<String> changed = new ArrayList<>(base);
            List<String> target = new ArrayList<>(base);
            for (int site = 6; site + 12 < n; site += 12) {
                changed.set(site, freshLine());
                // target-only edit that moves later lines: insert or delete just before the next site
                int gap = site + 6;
                if (rnd.nextBoolean()) {
                    target.set(gap, "T" + round + "_" + gap); // replace: no shift
                } else {
                    target.set(gap, target.get(gap)); // untouched
                }
            }
            // shift the target by prepending lines (the first hunk has full context: site >= 6)
            int pre = rnd.nextInt(4);
            for (int i = 0; i < pre; i++) {
                target.add(0, "P" + i);
            }
            String patchText = UnifiedDiff.format(base, changed, 3, "x", "y");
            Path fp = dir.resolve("p.diff");
            Files.writeString(fp, patchText);
            Path ft = write("t", target);
            Out gnu = run(dir, List.of("patch", "--fuzz=0", "-F0", "-s", "-o", "gnu.out", "t", "p.diff"));
            Patch parsed = UnifiedDiff.parse(patchText);
            Patcher.Result mine = Patcher.apply(target, parsed, 50);
            boolean gnuOk = gnu.code == 0;
            assertEquals(gnuOk, mine.ok(), "round " + round + " gnu: " + gnu.stdout);
            if (gnuOk) {
                assertEquals(Files.readString(dir.resolve("gnu.out")), Lines.text(mine.lines()), "round " + round);
                if (pre > 0) {
                    shifted++;
                }
            }
            assertTrue(ft.toFile().exists());
        }
        assertTrue(shifted >= 20, "only " + shifted + " offset applications compared");
    }

    private static String mergeGnu(Path dir, String ours, String base, String theirs) throws Exception {
        Out o = run(dir, List.of("diff3", "-m", "-L", "ours", "-L", "base", "-L", "theirs", ours, base, theirs));
        assertTrue(o.code == 0 || o.code == 1, o.stdout);
        return o.stdout;
    }

    @Test
    void mergeMatchesGnuDiff3OnDifferingEdits() throws Exception {
        Random rnd = new Random(31);
        int conflicts = 0;
        int clean = 0;
        int skipped = 0;
        for (int round = 0; round < 250; round++) {
            List<String> base = baseFile(rnd, 8 + rnd.nextInt(14));
            List<String> ours = edit(rnd, base, rnd.nextInt(4));
            List<String> theirs = edit(rnd, base, rnd.nextInt(4));
            java.util.Set<String> bothDeleted = new java.util.HashSet<>(base);
            bothDeleted.removeAll(ours);
            bothDeleted.retainAll(theirsMissing(base, theirs));
            if (!bothDeleted.isEmpty()) {
                skipped++; // the same base line deleted on both sides is an identical change
                continue;
            }
            write("ours", ours);
            write("base", base);
            write("theirs", theirs);
            List<Merge3.Chunk> chunks = Merge3.merge(base, ours, theirs);
            String gnu = mergeGnu(dir, "ours", "base", "theirs");
            // fresh unique lines never coincide, so no identical-change cases occur (GNU prints those
            // as base/theirs conflicts, a documented quirk we deliberately do not copy)
            assertEquals(gnu, Merge3.render(chunks, "ours", "base", "theirs"),
                "round " + round + "\nbase=" + base + "\nours=" + ours + "\ntheirs=" + theirs);
            if (Merge3.hasConflicts(chunks)) {
                conflicts++;
            } else {
                clean++;
            }
        }
        assertTrue(conflicts >= 40 && clean >= 40,
            "mix conflicts=" + conflicts + " clean=" + clean + " skipped=" + skipped);
    }

    private static java.util.Set<String> theirsMissing(List<String> base, List<String> theirs) {
        java.util.Set<String> missing = new java.util.HashSet<>(base);
        missing.removeAll(theirs);
        return missing;
    }
}
