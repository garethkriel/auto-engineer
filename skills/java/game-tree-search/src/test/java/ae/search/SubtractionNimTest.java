package ae.search;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SubtractionNimTest {
    @Test
    void movesAreTakeAmountsUpToMaxAndStones() {
        assertArrayEquals(new int[] {1, 2, 3}, new SubtractionNim(10, 3, false).moves());
        assertArrayEquals(new int[] {1, 2}, new SubtractionNim(2, 3, false).moves());
        assertArrayEquals(new int[0], new SubtractionNim(0, 3, false).moves());
    }

    @Test
    void emptyPileDecidesByVariant() {
        assertEquals(Position.Status.MOVER_LOST, new SubtractionNim(0, 3, false).status());
        assertEquals(Position.Status.MOVER_WON, new SubtractionNim(0, 3, true).status());
        assertEquals(Position.Status.ONGOING, new SubtractionNim(1, 3, true).status());
    }

    @Test
    void playReducesPileAndAdvancesPly() {
        Position p = new SubtractionNim(10, 3, false).play(3);
        assertEquals(1, p.ply());
        assertArrayEquals(new int[] {1, 2, 3}, p.moves());
        assertEquals(Position.Status.MOVER_LOST, new SubtractionNim(3, 3, false).play(3).status());
    }

    @Test
    void sameStonesAtDifferentPliesHaveDifferentKeys() {
        // Both piles hold 4 stones: one after 1 move (5 -> 4), the other after 2 moves (6 -> 5 -> 4).
        Position a = new SubtractionNim(5, 3, false).play(1);          // 4 stones, ply 1
        Position b = new SubtractionNim(6, 3, false).play(1).play(1);  // 4 stones, ply 2
        assertNotEquals(a.key(), b.key(), "terminal scores depend on ply, so the key must too");
    }

    @Test
    void illegalMovesAreRejected() {
        Position p = new SubtractionNim(2, 3, false);
        assertThrows(IllegalArgumentException.class, () -> p.play(0));
        assertThrows(IllegalArgumentException.class, () -> p.play(3));
        assertThrows(IllegalArgumentException.class, () -> new SubtractionNim(-1, 3, false));
        assertThrows(IllegalArgumentException.class, () -> new SubtractionNim(5, 0, false));
    }

    @Test
    void literalMinimaxScores() {
        // 3 stones, take up to 3: mover takes all at ply 0, the new position (ply 1) is lost for the opponent.
        assertEquals(AlphaBeta.WIN - 1, Minimax.value(new SubtractionNim(3, 3, false), 10));
        // 4 stones, max 3: whatever the mover takes (1..3) the opponent takes the rest at ply 1, ending at ply 2.
        assertEquals(-(AlphaBeta.WIN - 2), Minimax.value(new SubtractionNim(4, 3, false), 10));
        // Misere, 1 stone: the mover must take it, then the opponent (ply 1) is the one who "won" the empty pile.
        assertEquals(-(AlphaBeta.WIN - 1), Minimax.value(new SubtractionNim(1, 3, true), 10));
        // Misere, 2 stones: take 1 leaving 1 -> opponent forced to take the last -> mover wins at ply 2.
        assertEquals(AlphaBeta.WIN - 2, Minimax.value(new SubtractionNim(2, 3, true), 10));
    }
}
