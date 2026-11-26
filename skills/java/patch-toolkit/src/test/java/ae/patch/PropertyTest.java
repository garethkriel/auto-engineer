package ae.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Algebraic laws on random small-alphabet inputs (repeated lines, so ambiguity is exercised). */
class PropertyTest {
    private static List<String> randomLines(Random rnd, int max) {
        List<String> out = new ArrayList<>();
        for (int i = rnd.nextInt(max); i > 0; i--) {
            out.add("" + (char) ('a' + rnd.nextInt(3)));
        }
        return out;
    }

    @Test
    void formatParseApplyRoundTripsAndReverses() {
        Random rnd = new Random(5);
        for (int round = 0; round < 500; round++) {
            List<String> a = randomLines(rnd, 15);
            List<String> b = randomLines(rnd, 15);
            int ctx = rnd.nextInt(4);
            String text = UnifiedDiff.format(a, b, ctx, "x", "y");
            if (a.equals(b)) {
                assertEquals("", text);
                continue;
            }
            Patch p = UnifiedDiff.parse(text);
            assertEquals(text, UnifiedDiff.render(p), "parse/render is lossless");
            Patcher.Result fwd = Patcher.apply(a, p, 0);
            assertTrue(fwd.ok(), text);
            assertEquals(b, fwd.lines());
            Patcher.Result back = Patcher.apply(b, p.reverse(), 0);
            assertTrue(back.ok());
            assertEquals(a, back.lines());
            // the reversed patch is a valid diff of b to a: same hunk bodies as a fresh minimal diff
            // would only hold without ambiguity, so check its counts instead
            for (Hunk h : p.reverse().hunks()) {
                assertEquals(h.oldCount(), h.oldLines().size());
                assertEquals(h.newCount(), h.newLines().size());
            }
        }
    }

    @Test
    void mergeIdentities() {
        Random rnd = new Random(9);
        int conflicts = 0;
        for (int round = 0; round < 500; round++) {
            List<String> base = randomLines(rnd, 10);
            List<String> x = randomLines(rnd, 10);
            List<String> y = randomLines(rnd, 10);
            assertEquals(x, flatten(Merge3.merge(base, x, base)));
            assertEquals(x, flatten(Merge3.merge(base, base, x)));
            assertFalse(Merge3.hasConflicts(Merge3.merge(base, x, x)));
            assertEquals(x, flatten(Merge3.merge(base, x, x)));
            // conflict-free merges are symmetric
            List<Merge3.Chunk> xy = Merge3.merge(base, x, y);
            List<Merge3.Chunk> yx = Merge3.merge(base, y, x);
            assertEquals(Merge3.hasConflicts(xy), Merge3.hasConflicts(yx));
            if (!Merge3.hasConflicts(xy)) {
                assertEquals(flatten(xy), flatten(yx));
            } else {
                conflicts++;
            }
        }
        assertTrue(conflicts > 100, "conflicts=" + conflicts);
    }

    @Test
    void cleanMergeKeepsBothSidesEditsOnDisjointRegions() {
        Random rnd = new Random(13);
        for (int round = 0; round < 300; round++) {
            List<String> base = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                base.add("b" + i);
            }
            List<String> ours = new ArrayList<>(base);
            List<String> theirs = new ArrayList<>(base);
            List<String> expected = new ArrayList<>(base);
            // sites 0,3,6,..: even site edited by ours, odd by theirs; one kept line between neighbours
            for (int site = 27; site >= 0; site -= 3) {
                if (rnd.nextInt(3) == 0) {
                    continue;
                }
                String fresh = "r" + round + "_" + site;
                boolean mine = (site / 3) % 2 == 0;
                (mine ? ours : theirs).set(site, fresh);
                expected.set(site, fresh);
            }
            assertEquals(expected, flatten(Merge3.merge(base, ours, theirs)));
        }
    }

    private static List<String> flatten(List<Merge3.Chunk> chunks) {
        List<String> out = new ArrayList<>();
        for (Merge3.Chunk c : chunks) {
            if (c instanceof Merge3.Chunk.Stable s) {
                out.addAll(s.lines());
            } else {
                throw new AssertionError("unexpected conflict");
            }
        }
        return out;
    }
}
