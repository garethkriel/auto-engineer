package ae.cfg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DominatorsTest {
    private static Cfg cfg(String src) {
        return new Cfg(Function.parse(src));
    }

    private static int[] idoms(Dominators d, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = d.idom(i);
        }
        return out;
    }

    @Test
    void cfgEdgesAndOrder() {
        Cfg c = cfg(Fixtures.DIAMOND);
        assertEquals(4, c.size());
        assertEquals(List.of(1, 2), c.succ(0));
        assertEquals(List.of(3), c.succ(1));
        assertEquals(List.of(), c.succ(3));
        assertEquals(List.of(1, 2), c.pred(3));
        assertEquals(List.of(0, 1, 2, 3), c.rpo().stream().sorted().toList());
        assertEquals(0, c.rpo().get(0));
        assertEquals(3, c.rpo().get(3));           // join after both arms
        assertEquals(2, c.index("right"));
        assertThrows(IllegalArgumentException.class, () -> c.index("nope"));
    }

    @Test
    void duplicateBranchTargetsGiveOneEdge() {
        Cfg c = cfg("func f(a)\nb:\n  br a, c, c\nc:\n  ret a\n");
        assertEquals(List.of(1), c.succ(0));
        assertEquals(List.of(0), c.pred(1));
    }

    @Test
    void rpoOmitsUnreachableBlocks() {
        Cfg c = cfg(Fixtures.UNREACHABLE);
        assertEquals(5, c.size());
        assertFalse(c.reachable(4));
        assertEquals(4, c.rpo().size());
        assertFalse(c.rpo().contains(4));
        assertEquals(List.of(1, 2, 4), c.pred(3));   // preds still list the dead block
    }

    @Test
    void diamond() {
        Cfg c = cfg(Fixtures.DIAMOND);
        Dominators d = new Dominators(c);
        assertArrayEquals(new int[] {-1, 0, 0, 0}, idoms(d, 4));
        assertEquals(Set.of(), d.frontier(0));
        assertEquals(Set.of(3), d.frontier(1));
        assertEquals(Set.of(3), d.frontier(2));
        assertEquals(Set.of(), d.frontier(3));
        assertEquals(List.of(1, 2, 3), d.children(0));
        assertTrue(d.dominates(0, 3));
        assertTrue(d.dominates(3, 3));
        assertFalse(d.dominates(1, 3));
        assertFalse(d.dominates(3, 0));
    }

    @Test
    void countedLoop() {
        Dominators d = new Dominators(cfg(Fixtures.LOOP));
        assertArrayEquals(new int[] {-1, 0, 1, 1}, idoms(d, 4));
        assertEquals(Set.of(1), d.frontier(1));      // the header is in its own frontier
        assertEquals(Set.of(1), d.frontier(2));
        assertEquals(Set.of(), d.frontier(0));
        assertEquals(Set.of(), d.frontier(3));
        assertEquals(List.of(2, 3), d.children(1));
    }

    @Test
    void irreducibleCycleHasNoDominatingEntry() {
        Dominators d = new Dominators(cfg(Fixtures.IRREDUCIBLE));
        assertArrayEquals(new int[] {-1, 0, 0, 2}, idoms(d, 4));
        assertEquals(Set.of(2), d.frontier(1));
        assertEquals(Set.of(1), d.frontier(2));
        assertFalse(d.dominates(1, 2));
        assertFalse(d.dominates(2, 1));
        assertTrue(d.dominates(2, 3));
    }

    @Test
    void unreachableBlocksAreOutsideTheTree() {
        Dominators d = new Dominators(cfg(Fixtures.UNREACHABLE));
        assertArrayEquals(new int[] {-1, 0, 0, 0, -1}, idoms(d, 5));
        assertFalse(d.dominates(4, 3));
        assertFalse(d.dominates(0, 4));
        assertFalse(d.dominates(4, 4));
        assertEquals(Set.of(), d.frontier(4));
        assertEquals(Set.of(3), d.frontier(1));      // the dead predecessor adds nothing
        assertEquals(List.of(1, 2, 3), d.children(0));
    }

    @Test
    void entrySelfLoopPutsEntryInItsOwnFrontier() {
        Dominators d = new Dominators(cfg(Fixtures.SELF_LOOP));
        assertArrayEquals(new int[] {-1, 0}, idoms(d, 2));
        assertEquals(Set.of(0), d.frontier(0));
        assertEquals(Set.of(), d.frontier(1));
    }

    /** Linear chain of 40 blocks: recursion-free algorithms must cope, and idom is the previous block. */
    @Test
    void longChain() {
        StringBuilder sb = new StringBuilder("func c(a)\n");
        for (int i = 0; i < 40; i++) {
            sb.append("b").append(i).append(":\n  ").append(i == 39 ? "ret a" : "jmp b" + (i + 1)).append('\n');
        }
        Dominators d = new Dominators(cfg(sb.toString()));
        for (int i = 1; i < 40; i++) {
            assertEquals(i - 1, d.idom(i));
            assertEquals(Set.of(), d.frontier(i));
        }
    }

    @Test
    void randomGraphsMatchDefinitionOracles() {
        Random rnd = new Random(20261126L);
        int unreachable = 0;
        int cyclic = 0;
        int multi = 0;
        for (int round = 0; round < 400; round++) {
            Cfg c = cfg(Gen.randomCfg(rnd));
            Dominators d = new Dominators(c);
            boolean hasDead = false;
            for (int b = 0; b < c.size(); b++) {
                hasDead |= !c.reachable(b);
                assertEquals(Oracles.idom(c, b), d.idom(b), "idom of b" + b + " in round " + round);
                assertEquals(Oracles.frontier(c, b), d.frontier(b), "frontier of b" + b + " in round " + round);
                for (int a = 0; a < c.size(); a++) {
                    assertEquals(Oracles.dominates(c, a, b), d.dominates(a, b), a + " dom " + b + " round " + round);
                }
            }
            unreachable += hasDead ? 1 : 0;
            cyclic += c.pred(0).isEmpty() ? 0 : 1;
            multi += c.size() >= 5 ? 1 : 0;
        }
        assertTrue(unreachable > 40, "graphs with dead blocks: " + unreachable);
        assertTrue(cyclic > 10, "graphs with a back edge into the entry: " + cyclic);
        assertTrue(multi > 100, "graphs with >= 5 blocks: " + multi);
    }

    @Test
    void childrenPartitionTheReachableBlocks() {
        Random rnd = new Random(7);
        for (int round = 0; round < 200; round++) {
            Cfg c = cfg(Gen.randomCfg(rnd));
            Dominators d = new Dominators(c);
            int edges = 0;
            for (int b = 0; b < c.size(); b++) {
                for (int ch : d.children(b)) {
                    assertEquals(b, d.idom(ch));
                    edges++;
                }
            }
            int reachable = (int) java.util.stream.IntStream.range(0, c.size()).filter(c::reachable).count();
            assertEquals(reachable - 1, edges);
        }
    }
}
