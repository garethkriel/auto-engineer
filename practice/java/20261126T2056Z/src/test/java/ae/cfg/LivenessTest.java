package ae.cfg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LivenessTest {
    @Test
    void countedLoop() {
        Cfg c = new Cfg(Function.parse(Fixtures.LOOP));
        Liveness lv = new Liveness(c);
        assertEquals(Set.of("n"), lv.liveIn(0));
        assertEquals(Set.of("i", "n", "one"), lv.liveOut(0));
        assertEquals(Set.of("i", "n", "one"), lv.liveIn(1));
        assertEquals(Set.of("i", "n", "one"), lv.liveOut(1));
        assertEquals(Set.of("i", "n", "one"), lv.liveIn(2));
        assertEquals(Set.of("i"), lv.liveIn(3));
        assertEquals(Set.of(), lv.liveOut(3));
        assertEquals(List.of(Set.of("i", "n"), Set.of("i", "n", "one"), Set.of("i", "n", "one")), lv.liveAfter(0));
        // head: c = lt i, n ; br c, ... -> after the lt only c and the loop-carried names are live.
        assertEquals(List.of(Set.of("c", "i", "n", "one"), Set.of("i", "n", "one")), lv.liveAfter(1));
        assertEquals(List.of(Set.of("i", "n", "one"), Set.of("i", "n", "one")), lv.liveAfter(2));
        assertEquals(List.of(Set.of()), lv.liveAfter(3));
    }

    @Test
    void diamondJoinKeepsOnlyWhatIsRead() {
        Cfg c = new Cfg(Function.parse(Fixtures.DIAMOND));
        Liveness lv = new Liveness(c);
        assertEquals(Set.of("a"), lv.liveIn(0));
        assertEquals(Set.of("x"), lv.liveOut(1));
        assertEquals(Set.of("x"), lv.liveIn(3));
        assertEquals(Set.of(), lv.liveIn(1));           // x is defined first thing in the arm
        assertEquals(Set.of("c"), lv.liveAfter(0).get(0));
    }

    @Test
    void redefinitionKillsLiveness() {
        Function f = Function.parse("func k(a)\nb:\n  x = const 1\n  x = const 2\n  ret x\n");
        Liveness lv = new Liveness(new Cfg(f));
        assertEquals(List.of(Set.of(), Set.of("x"), Set.of()), lv.liveAfter(0));
        assertEquals(Set.of(), lv.liveIn(0));
    }

    @Test
    void unreachableBlocksAreAnalysedToo() {
        Function f = Function.parse("func d(a)\nb:\n  ret a\nx:\n  y = add a, a\n  ret y\n");
        Liveness lv = new Liveness(new Cfg(f));
        assertEquals(Set.of("a"), lv.liveIn(1));
        assertEquals(Set.of("y"), lv.liveAfter(1).get(0));
    }

    @Test
    void randomProgramsMatchBruteForcePathSearch() {
        Random rnd = new Random(31337L);
        int checkedPoints = 0;
        int nonEmpty = 0;
        for (int round = 0; round < 250; round++) {
            String src = round % 2 == 0 ? Gen.randomCfg(rnd) : Gen.structured(rnd, 2, 4);
            Function f = Function.parse(src);
            Cfg c = new Cfg(f);
            Liveness lv = new Liveness(c);
            for (int b = 0; b < c.size(); b++) {
                assertEquals(Oracles.liveIn(f, c, b), lv.liveIn(b), "liveIn b" + b + " round " + round + "\n" + src);
                List<Set<String>> after = lv.liveAfter(b);
                assertEquals(f.blocks().get(b).instrs().size(), after.size());
                for (int i = 0; i < after.size(); i++) {
                    assertEquals(Oracles.liveAfter(f, c, b, i), after.get(i), "liveAfter b" + b + "." + i + " round " + round + "\n" + src);
                    checkedPoints++;
                    nonEmpty += after.get(i).isEmpty() ? 0 : 1;
                }
                // liveOut(b) is what is live after the terminator.
                assertEquals(after.get(after.size() - 1), lv.liveOut(b));
            }
        }
        assertTrue(checkedPoints > 3000, "points: " + checkedPoints);
        assertTrue(nonEmpty > checkedPoints / 2, "non-empty live sets: " + nonEmpty + " of " + checkedPoints);
    }
}
