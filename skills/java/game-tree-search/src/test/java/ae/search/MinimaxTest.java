package ae.search;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MinimaxTest {
    @Test
    void ticTacToeIsADraw() {
        assertEquals(0, Minimax.value(ConnectK.ticTacToe(), 9));
    }

    @Test
    void nodeCounterMatchesPerftWhenSearchingToTheEnd() {
        Minimax m = new Minimax();
        assertEquals(0, m.search(ConnectK.ticTacToe(), 9));
        assertEquals(549946L, m.nodes());
    }

    @Test
    void depthLimitUsesTheHeuristic() {
        Position p = ConnectK.ticTacToe();
        assertEquals(p.heuristic(), Minimax.value(p, 0));
        long expectedNodes = 1 + 9; // root + children at depth 1
        Minimax m = new Minimax();
        m.search(p, 1);
        assertEquals(expectedNodes, m.nodes());
    }

    @Test
    void immediateWinScoresWinMinusPly() {
        Position p = ConnectK.parse(3, false,
            "XX.",
            "OO.",
            "...");
        // X to move (ply 4) wins at once: the new position has ply 5 and the opponent has lost.
        assertEquals(AlphaBeta.WIN - 5, Minimax.value(p, 1));
        assertEquals(AlphaBeta.WIN - 5, Minimax.value(p, 5));
    }

    @Test
    void winningMoveIsFoundAmongOthers() {
        Position p = ConnectK.parse(3, false,
            "XX.",
            "...",
            "O.O");
        // X=2, O=2 so X moves (ply 4) and wins at cell 2: the new position has ply 5.
        assertEquals(AlphaBeta.WIN - 5, Minimax.value(p, 3));
    }

    @Test
    void ignoringAThreatLosesAtTheNextPly() {
        // X=2 (cells 0,2), O=2 (cells 1,4): X to move at ply 4; O threatens column 1 (cells 1,4,7).
        Position p = ConnectK.parse(3, false,
            "XOX",
            ".O.",
            "...");
        Position ignored = p.play(6);
        // O (now the mover, ply 5) wins at once by playing 7: the final position has ply 6.
        assertEquals(AlphaBeta.WIN - 6, Minimax.value(ignored, 1));
        Position blocked = p.play(7);
        assertTrue(Math.abs(Minimax.value(blocked, 1)) < AlphaBeta.WIN / 2, "O has no immediate win after the block");
    }
}
