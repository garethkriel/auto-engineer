package ae.diff;

import static ae.diff.TestSupport.lcs;
import static ae.diff.TestSupport.lines;
import static ae.diff.TestSupport.mutate;
import static ae.diff.TestSupport.randomLines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DiffTest {

    private static List<String> project(List<Diff.Edit> script, boolean wantOld) {
        List<String> out = new ArrayList<>();
        for (Diff.Edit e : script) {
            if (e.op() == Diff.Op.KEEP
                    || (wantOld && e.op() == Diff.Op.DELETE)
                    || (!wantOld && e.op() == Diff.Op.INSERT)) {
                out.add(e.line());
            }
        }
        return out;
    }

    @Test
    void classicMyersExampleHasDistanceFive() {
        List<String> a = lines("A B C A B B A");
        List<String> b = lines("C B A B A C");
        assertEquals(5, Diff.distance(a, b));
        List<Diff.Edit> script = Diff.diff(a, b);
        assertEquals(a, project(script, true));
        assertEquals(b, project(script, false));
    }

    @Test
    void emptyAndIdenticalInputs() {
        assertTrue(Diff.diff(List.of(), List.of()).isEmpty());
        assertEquals(0, Diff.distance(lines("x y"), lines("x y")));
        List<Diff.Edit> ins = Diff.diff(List.of(), lines("p q"));
        assertEquals(List.of(new Diff.Edit(Diff.Op.INSERT, "p"), new Diff.Edit(Diff.Op.INSERT, "q")), ins);
        List<Diff.Edit> del = Diff.diff(lines("p q"), List.of());
        assertEquals(List.of(new Diff.Edit(Diff.Op.DELETE, "p"), new Diff.Edit(Diff.Op.DELETE, "q")), del);
        assertTrue(Diff.regions(lines("x y"), lines("x y")).isEmpty());
    }

    @Test
    void singleReplacementIsDeleteThenInsertInOneRegion() {
        List<String> a = lines("a b c");
        List<String> b = lines("a X c");
        assertEquals(List.of(
                new Diff.Edit(Diff.Op.KEEP, "a"),
                new Diff.Edit(Diff.Op.DELETE, "b"),
                new Diff.Edit(Diff.Op.INSERT, "X"),
                new Diff.Edit(Diff.Op.KEEP, "c")), Diff.diff(a, b));
        assertEquals(List.of(new Diff.Region(1, 2, List.of("X"))), Diff.regions(a, b));
    }

    @Test
    void scriptIsMinimalAndReproducesBothSidesOnRandomInputs() {
        Random r = new Random(7);
        for (int i = 0; i < 600; i++) {
            int alphabet = 1 + r.nextInt(5);
            List<String> a = randomLines(r, 25, alphabet);
            List<String> b = i % 2 == 0 ? randomLines(r, 25, alphabet) : mutate(r, a, alphabet);
            List<Diff.Edit> script = Diff.diff(a, b);
            String ctx = "a=" + a + " b=" + b;
            assertEquals(a, project(script, true), ctx);
            assertEquals(b, project(script, false), ctx);
            int changes = (int) script.stream().filter(e -> e.op() != Diff.Op.KEEP).count();
            assertEquals(a.size() + b.size() - 2 * lcs(a, b), changes, ctx);
            assertEquals(changes, Diff.distance(a, b), ctx);
        }
    }

    @Test
    void regionsAreSortedSeparatedAndRebuildTheNewSide() {
        Random r = new Random(11);
        int nonTrivial = 0;
        for (int i = 0; i < 500; i++) {
            List<String> a = randomLines(r, 30, 4);
            List<String> b = mutate(r, a, 4);
            List<Diff.Region> regions = Diff.regions(a, b);
            List<String> rebuilt = new ArrayList<>();
            int cursor = 0;
            for (int k = 0; k < regions.size(); k++) {
                Diff.Region g = regions.get(k);
                assertTrue(g.aStart() <= g.aEnd());
                assertFalse(g.aStart() == g.aEnd() && g.replacement().isEmpty(), "empty region");
                if (k > 0) {
                    assertTrue(g.aStart() > cursor || regions.get(k - 1).aEnd() < g.aStart(),
                            "regions must be separated by at least one kept line");
                }
                rebuilt.addAll(a.subList(cursor, g.aStart()));
                rebuilt.addAll(g.replacement());
                cursor = g.aEnd();
            }
            rebuilt.addAll(a.subList(cursor, a.size()));
            assertEquals(b, rebuilt, "a=" + a + " b=" + b);
            if (!regions.isEmpty()) {
                nonTrivial++;
            }
        }
        assertTrue(nonTrivial > 300, "generator must produce real differences, got " + nonTrivial);
    }

    @Test
    void largeNearlyIdenticalInputsAreFast() {
        List<String> a = new ArrayList<>();
        for (int i = 0; i < 40_000; i++) {
            a.add("line " + i);
        }
        List<String> b = new ArrayList<>(a);
        for (int i = 0; i < 10; i++) {
            b.remove(3_000 * i + 5 - i);
            b.add(3_000 * i + 100, "new " + i);
        }
        long t0 = System.nanoTime();
        int d = Diff.distance(a, b);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(20, d);
        assertTrue(ms < 5_000, "O(ND) diff took " + ms + " ms");
        assertEquals(20, Diff.regions(a, b).size());
    }
}
