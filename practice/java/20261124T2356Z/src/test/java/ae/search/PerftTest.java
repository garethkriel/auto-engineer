package ae.search;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PerftTest {
    @Test
    void ticTacToeLevelCounts() {
        // Nodes per ply (games stop at a win or a full board): the values sum to 549946.
        long[] expected = {1, 9, 72, 504, 3024, 15120, 54720, 148176, 200448, 127872};
        long total = 0;
        for (int d = 0; d < expected.length; d++) {
            assertEquals(expected[d], Perft.count(ConnectK.ticTacToe(), d), "ply " + d);
            total += expected[d];
        }
        assertEquals(549946L, total);
    }

    @Test
    void nimCountsCompositions() {
        // 4 stones, take 1 or 2: complete games are the compositions 1111, 112, 121, 211, 22 -> 5.
        // Nodes by ply: 1, 2, 4, 4, 1 -> 12 (ply 3 has 1+1+2, ply 4 only the path 1,1,1,1).
        SubtractionNim p = new SubtractionNim(4, 2, false);
        assertEquals(5, Perft.games(p));
        assertEquals(12, Perft.nodes(p));
        assertEquals(1, Perft.count(p, 4));
        assertEquals(0, Perft.count(p, 5));
    }

    @Test
    void negativeDepthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Perft.count(ConnectK.ticTacToe(), -1));
    }
}
