package ae.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ConnectKTest {
    @Test
    void ticTacToeGameTreeCounts() {
        Position root = ConnectK.ticTacToe();
        // Published: 255168 complete games, 549946 nodes (root included).
        long[] result = new long[3];
        countGames(root, result);
        assertEquals(131184L, result[0], "games won by the first player X");
        assertEquals(77904L, result[1], "games won by the second player O");
        assertEquals(46080L, result[2], "drawn games");
        assertEquals(549946L, Perft.nodes(root));
    }

    private static void countGames(Position p, long[] r) {
        if (p.status() != Position.Status.ONGOING) {
            if (p.status() == Position.Status.DRAW) {
                r[2]++;
            } else if (p.ply() % 2 == 1) {
                r[0]++; // X moved last (ply odd) and the mover (O) has lost
            } else {
                r[1]++;
            }
            return;
        }
        for (int m : p.moves()) {
            countGames(p.play(m), r);
        }
    }

    @Test
    void perftByDepthForConnectFour() {
        // No four-in-a-row is possible before ply 7, so the first levels are exact powers of 7.
        Position root = ConnectK.connectFour();
        long[] expected = {1, 7, 49, 343, 2401, 16807, 117649};
        for (int d = 0; d < expected.length; d++) {
            assertEquals(expected[d], Perft.count(root, d), "depth " + d);
        }
    }

    @Test
    void gravityDropsToLowestFreeCell() {
        Position p = ConnectK.parse(3, true,
            "...",
            "...",
            "...");
        Position a = p.play(1); // X in column 1, bottom
        Position b = a.play(1); // O on top of it
        assertEquals(ConnectK.parse(3, true,
            "...",
            ".O.",
            ".X."), b);
    }

    @Test
    void gravityColumnFullIsNotAMove() {
        Position p = ConnectK.parse(3, true,
            "XO.",
            "OX.",
            "XO.");
        assertArrayEquals(new int[] {2}, p.moves());
    }

    @Test
    void freeBoardMovesAreEmptyCells() {
        Position p = ConnectK.parse(3, false,
            "X.O",
            ".X.",
            "...");
        assertArrayEquals(new int[] {1, 3, 5, 6, 7, 8}, p.moves());
    }

    @Test
    void detectsEveryLineDirection() {
        String[][] boards = {
            {"XXX", "OO.", "..."},             // horizontal
            {"XO.", "XO.", "X.."},             // vertical
            {"XO.", "OX.", "..X"},             // diagonal down
            {"..X", "OX.", "XO."},             // diagonal up
        };
        for (String[] rows : boards) {
            Position p = ConnectK.parse(3, false, rows);
            assertEquals(Position.Status.MOVER_LOST, p.status(), Arrays.toString(rows));
        }
    }

    @Test
    void winByTheSecondPlayerIsAlsoMoverLost() {
        Position p = ConnectK.parse(3, false,
            "XX.",
            "OOO",
            "X..");
        assertEquals(Position.Status.MOVER_LOST, p.status());
        assertEquals(6, p.ply());
    }

    @Test
    void fullBoardWithoutLineIsDraw() {
        Position p = ConnectK.parse(3, false,
            "XOX",
            "XOO",
            "OXX");
        assertEquals(Position.Status.DRAW, p.status());
        assertEquals(0, p.moves().length);
    }

    @Test
    void lineNeedsExactlyKInARow() {
        Position p = ConnectK.parse(4, false,
            "XXX.",
            "OOO.",
            "X...",
            "....");
        assertEquals(Position.Status.ONGOING, p.status());
        assertEquals(7, p.ply(), "4 X + 3 O");
    }

    @Test
    void parseRejectsImpossibleBoards() {
        assertBad(3, false, "XXX", "...", "...");          // 3 X, 0 O
        assertBad(3, false, "OO.", "X..", "...");          // more O than X
        assertBad(3, false, "XXX", "OOO", "X..");          // both players have a line
        assertBad(3, false, "XXX", "OO.", "O..");          // X has a line but O moved after it (3 X, 3 O)
        assertBad(3, true, "X..", "...", "...");           // floating piece under gravity
        assertBad(3, false, "XO", "...", "...");           // ragged row
        assertBad(3, false, "XO?", "...", "...");          // unknown character
        assertBad(1, false, "...", "...", "...");          // k < 2
    }

    @Test
    void parseAcceptsFloatingPiecesWithoutGravity() {
        Position p = ConnectK.parse(3, false, "X..", "...", "...");
        assertEquals(1, p.ply());
    }

    private static void assertBad(int k, boolean gravity, String... rows) {
        assertThrows(IllegalArgumentException.class, () -> ConnectK.parse(k, gravity, rows),
            Arrays.toString(rows));
    }

    @Test
    void equalBoardsAreEqualAndShareKey() {
        Position a = ConnectK.ticTacToe().play(0).play(4).play(8);
        Position b = ConnectK.ticTacToe().play(8).play(4).play(0);
        assertEquals(a, b, "same X pieces on 0,8 and O on 4, reached in another order");
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a.key(), b.key());
        assertNotEquals(a.key(), ConnectK.ticTacToe().play(0).play(4).play(7).key());
    }

    @Test
    void keyDeterminesEverythingTheSearchReads() {
        // Random playouts on three boards: any two positions with the same key agree on ply, status,
        // moves and heuristic.
        SplittableRandom rnd = new SplittableRandom(7);
        Position[] roots = {ConnectK.ticTacToe(), ConnectK.start(4, 4, 3, true), ConnectK.connectFour()};
        int distinct = 0;
        for (Position root : roots) {
            // One map per board: empty boards of different sizes legitimately share key 0.
            Map<Long, Position> seen = new HashMap<>();
            for (int game = 0; game < 400; game++) {
                Position p = root;
                while (true) {
                    Position old = seen.putIfAbsent(p.key(), p);
                    if (old == null) {
                        distinct++;
                    } else {
                        assertEquals(old.ply(), p.ply());
                        assertEquals(old.status(), p.status());
                        assertArrayEquals(old.moves(), p.moves());
                        assertEquals(old.heuristic(), p.heuristic());
                    }
                    int[] ms = p.moves();
                    if (ms.length == 0) {
                        break;
                    }
                    p = p.play(ms[rnd.nextInt(ms.length)]);
                }
            }
        }
        assertTrue(distinct > 3000, "playouts should visit many distinct positions: " + distinct);
    }

    @Test
    void heuristicIsFromTheMoversViewAndBounded() {
        // Plies: X, O, X so the mover is O. Windows of length 3 on 3x3: 3 rows, 3 columns, 2 diagonals.
        // Row 0 X,X,. -> X-only, 2 pieces -> +4.      Row 1 O,.,. -> O-only, 1 piece -> -1 (O).
        // Column 0 X,O,. -> mixed, dead.              Column 1 X,.,. -> +1.
        // Main diagonal X,.,. -> +1.                  Rows 2, column 2, anti-diagonal are empty.
        // X-O = (4+1+1) - 1 = +5 from X's view; the mover is O -> -5.
        Position p = ConnectK.parse(3, false,
            "XX.",
            "O..",
            "...");
        assertEquals(3, p.ply());
        assertEquals(-5, p.heuristic());
        SplittableRandom rnd = new SplittableRandom(3);
        for (int g = 0; g < 200; g++) {
            Position q = ConnectK.connectFour();
            while (q.status() == Position.Status.ONGOING) {
                assertTrue(Math.abs(q.heuristic()) < AlphaBeta.WIN / 2);
                int[] ms = q.moves();
                q = q.play(ms[rnd.nextInt(ms.length)]);
            }
        }
    }
}
