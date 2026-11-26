package ae.cfg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LoopsTest {
    private static Loops loops(String src) {
        Cfg c = new Cfg(Function.parse(src));
        return new Loops(c, new Dominators(c));
    }

    private static final String NESTED = """
            func n(a)
            entry:
              jmp outer
            outer:
              br a, inner, done
            inner:
              br a, inner2, outerTail
            inner2:
              jmp inner
            outerTail:
              jmp outer
            done:
              ret a
            """;                      // entry0 outer1 inner2 inner2b3 outerTail4 done5

    @Test
    void countedLoop() {
        Loops l = loops(Fixtures.LOOP);
        assertEquals(List.of(new Loops.Loop(1, Set.of(1, 2), List.of(2))), l.loops());
        assertEquals(List.of(0, 1, 1, 0), List.of(l.depth(0), l.depth(1), l.depth(2), l.depth(3)));
        assertTrue(l.reducible());
    }

    @Test
    void nestedLoops() {
        Loops l = loops(NESTED);
        assertEquals(2, l.loops().size());
        assertEquals(new Loops.Loop(1, Set.of(1, 2, 3, 4), List.of(4)), l.loops().get(0));
        assertEquals(new Loops.Loop(2, Set.of(2, 3), List.of(3)), l.loops().get(1));
        assertEquals(List.of(0, 1, 2, 2, 1, 0), List.of(l.depth(0), l.depth(1), l.depth(2), l.depth(3), l.depth(4), l.depth(5)));
        assertTrue(l.reducible());
    }

    @Test
    void twoBackEdgesToOneHeaderFormOneLoop() {
        Loops l = loops("""
                func m(a)
                h:
                  br a, p, q
                p:
                  br a, h, x
                q:
                  jmp h
                x:
                  ret a
                """);                 // h0 p1 q2 x3
        assertEquals(List.of(new Loops.Loop(0, Set.of(0, 1, 2), List.of(1, 2))), l.loops());
        assertEquals(1, l.depth(2));
        assertEquals(0, l.depth(3));
    }

    @Test
    void selfLoopAndIrreducibleCycle() {
        assertEquals(List.of(new Loops.Loop(0, Set.of(0), List.of(0))), loops(Fixtures.SELF_LOOP).loops());
        Loops irr = loops(Fixtures.IRREDUCIBLE);
        assertEquals(List.of(), irr.loops());
        assertFalse(irr.reducible());
        assertEquals(0, irr.depth(1));
    }

    @Test
    void deadCodeLoopIsIgnored() {
        Loops l = loops("func d(a)\nb:\n  ret a\nx:\n  jmp y\ny:\n  jmp x\n");
        assertEquals(List.of(), l.loops());
        assertTrue(l.reducible());
        assertEquals(0, l.depth(1));
    }

    @Test
    void randomGraphsMatchOracles() {
        Random rnd = new Random(99L);
        int withLoops = 0;
        int irreducible = 0;
        for (int round = 0; round < 500; round++) {
            Cfg c = new Cfg(Function.parse(Gen.randomCfg(rnd)));
            Dominators d = new Dominators(c);
            Loops l = new Loops(c, d);
            assertEquals(Oracles.reducible(c), l.reducible(), "round " + round);
            int[] depth = new int[c.size()];
            for (Loops.Loop loop : l.loops()) {
                assertEquals(Oracles.loopBody(c, loop.header(), loop.tails()), loop.blocks(), "loop " + loop + " round " + round);
                for (int t : loop.tails()) {
                    assertTrue(c.succ(t).contains(loop.header()) && d.dominates(loop.header(), t));
                }
                for (int b : loop.blocks()) {
                    depth[b]++;
                }
            }
            for (int b = 0; b < c.size(); b++) {
                assertEquals(depth[b], l.depth(b));
            }
            // Every back edge of the graph is accounted for by some loop tail.
            for (int t = 0; t < c.size(); t++) {
                for (int h : c.succ(t)) {
                    if (d.dominates(h, t)) {
                        final int tail = t;
                        final int header = h;
                        assertTrue(l.loops().stream().anyMatch(x -> x.header() == header && x.tails().contains(tail)));
                    }
                }
            }
            withLoops += l.loops().isEmpty() ? 0 : 1;
            irreducible += l.reducible() ? 0 : 1;
        }
        assertTrue(withLoops > 100, "graphs with loops: " + withLoops);
        assertTrue(irreducible > 10, "irreducible graphs: " + irreducible);
    }
}
