package ae.life;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class InvariantTest {
    private static Node leaf(boolean alive) {
        return alive ? Node.ALIVE : Node.DEAD;
    }

    private static Node level1(boolean a, boolean b, boolean c, boolean d) {
        return new Node(1, leaf(a), leaf(b), leaf(c), leaf(d),
                (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0));
    }

    private static void expect(Runnable r, String prefix) {
        IllegalStateException e = assertThrows(IllegalStateException.class, r::run);
        assertTrue(e.getMessage().startsWith(prefix), e.getMessage());
    }

    @Test
    void wellFormedNodesPass() {
        Node.checkLocal(level1(true, false, false, true));
        Node.checkLocal(Node.ALIVE);
        Node.checkLocal(Node.DEAD);
    }

    @Test
    void populationMismatchIsReported() {
        Node bad = new Node(1, Node.ALIVE, Node.DEAD, Node.DEAD, Node.DEAD, 2);
        expect(() -> Node.checkLocal(bad), "population");
    }

    @Test
    void childLevelMismatchIsReported() {
        Node l1 = level1(true, true, true, true);
        Node bad = new Node(2, l1, l1, l1, Node.DEAD, 12);
        expect(() -> Node.checkLocal(bad), "level");
    }

    @Test
    void memoOfWrongLevelIsReported() {
        Node n = level1(true, false, false, false);
        Node two = new Node(2, n, n, n, n, 4);
        two.memoFor(0)[0] = two; // result of a level-2 node must be level 1
        expect(() -> Node.checkLocal(two), "memo");
    }

    @Test
    void nonCanonicalNodeIsDetectedByTheUniverse() {
        Universe u = Universe.of(List.of(new Cell(0, 0), new Cell(1, 1)));
        u.checkInvariants();
        Node root = u.rootForTest();
        Node clone = new Node(root.level, root.nw, root.ne, root.sw, root.se, root.pop);
        u.replaceRootUnchecked(clone);
        expect(u::checkInvariants, "canonical");
    }

    @Test
    void rootWithWrongTotalIsDetectedByTheUniverse() {
        Universe u = Universe.of(List.of(new Cell(0, 0)));
        Node root = u.rootForTest();
        u.replaceRootUnchecked(new Node(root.level, root.nw, root.ne, root.sw, root.se, root.pop + 1));
        expect(u::checkInvariants, "population");
    }

    @Test
    void compactKeepsCellsShrinksTableAndKeepsStepping() {
        List<Cell> rp = Rle.parse("x = 3, y = 3\nb2o$2ob$bo!").cells();
        Universe u = Universe.of(rp);
        u.step(500);
        int before = u.nodeCount();
        List<Cell> cells = u.cells();
        u.compact();
        u.checkInvariants();
        assertEquals(cells, u.cells());
        assertTrue(u.nodeCount() < before, before + " -> " + u.nodeCount());
        u.step(603);
        NaiveLife naive = new NaiveLife(rp);
        naive.step(1103);
        assertEquals(naive.cells(), u.cells());
    }

    @Test
    void hashConsingSharesIdenticalSubtrees() {
        // 8x8 lattice of 8x8 tiles over [-32,32): identical tiles share every node, random tiles do not.
        List<Cell> glider = Rle.parse("x = 3, y = 3\nbo$2bo$3o!").cells();
        List<Cell> same = new java.util.ArrayList<>();
        List<Cell> different = new java.util.ArrayList<>();
        java.util.Random rnd = new java.util.Random(3);
        for (int ty = -4; ty < 4; ty++) {
            for (int tx = -4; tx < 4; tx++) {
                for (Cell c : glider) {
                    same.add(new Cell(8L * tx + c.x(), 8L * ty + c.y()));
                }
                for (int k = 0; k < 20; k++) {
                    different.add(new Cell(8L * tx + rnd.nextInt(8), 8L * ty + rnd.nextInt(8)));
                }
            }
        }
        Universe shared = Universe.of(same);
        Universe control = Universe.of(different);
        shared.compact(); // set() leaves superseded intermediate nodes in the table; keep only reachable ones
        control.compact();
        assertEquals(64 * 5, shared.population());
        assertEquals(6, shared.level());
        // tile path: <= 4 level-1, <= 4 level-2, 1 level-3 nodes, 3 uniform levels above, plus empty nodes 1..3
        assertTrue(shared.nodeCount() < 30, "shared " + shared.nodeCount());
        assertTrue(control.nodeCount() > 150, "control " + control.nodeCount());
        shared.checkInvariants();
        control.checkInvariants();
    }
}
