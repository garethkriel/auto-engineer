package ae.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GameTest {

    @Test
    void movetextAndFen() {
        Game g = new Game();
        for (String s : "e4 e5 Nf3 Nc6 Bb5 a6".split(" ")) {
            g.playSan(s);
        }
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6", g.moveText());
        assertEquals("r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4", g.position().toFen());
        assertEquals(Game.Status.ONGOING, g.status());
    }

    @Test
    void foolsMateEndsTheGame() {
        Game g = new Game();
        g.playSan("f3");
        g.playSan("e5");
        g.playSan("g4");
        g.playSan("Qh4#");
        assertEquals(Game.Status.CHECKMATE, g.status());
        assertThrows(IllegalStateException.class, () -> g.playSan("a3"));
    }

    @Test
    void illegalMoveLeavesGameUntouched() {
        Game g = new Game();
        assertThrows(IllegalArgumentException.class, () -> g.playSan("e5"));
        assertThrows(IllegalArgumentException.class, () -> g.playUci("e2e5"));
        assertEquals(Position.START_FEN, g.position().toFen());
        assertEquals("", g.moveText());
    }

    @Test
    void threefoldRepetition() {
        Game g = new Game();
        String[] cycle = {"Nf3", "Nf6", "Ng1", "Ng8"};
        for (String s : cycle) {
            g.playSan(s);
        }
        assertEquals(Game.Status.ONGOING, g.status(), "start position seen twice");
        for (int i = 0; i < 3; i++) {
            g.playSan(cycle[i]);
            assertEquals(Game.Status.ONGOING, g.status());
        }
        g.playSan(cycle[3]);
        assertEquals(Game.Status.DRAW_REPETITION, g.status());
    }

    @Test
    void fiftyMoveRule() {
        Game g = new Game("4k3/8/8/8/8/8/8/R3K3 w - - 98 80");
        g.playSan("Ra2");
        assertEquals(Game.Status.ONGOING, g.status());
        g.playSan("Ke7");
        assertEquals(Game.Status.DRAW_FIFTY_MOVES, g.status());
    }

    @Test
    void checkmateBeatsFiftyMoveDraw() {
        Game g = new Game("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 99 80");
        g.playSan("Ra8#");
        assertEquals(Game.Status.CHECKMATE, g.status());
    }

    @Test
    void stalemateAndInsufficientMaterial() {
        Game g = new Game("7k/8/5K2/8/8/8/8/6Q1 w - - 0 1");
        assertEquals(Game.Status.ONGOING, g.status());
        g.playSan("Qg6");
        assertEquals(Game.Status.STALEMATE, g.status());
        assertEquals(Game.Status.DRAW_INSUFFICIENT_MATERIAL, new Game("4k3/8/8/8/8/8/8/4KB2 w - - 0 1").status());
    }

    @Test
    void importsMovetextWithNumbersCommentsAndResult() {
        Game g = Game.fromMoveText("1.e4 e5 {king pawn} 2. Nf3 Nc6 3.Bb5 a6 1/2-1/2");
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6", g.moveText());
        Game h = Game.fromMoveText("1. e4 *");
        assertEquals("1. e4", h.moveText());
    }

    @Test
    void blackToMoveStartUsesEllipsis() {
        Game g = new Game("4k3/8/8/8/8/8/8/R3K3 b - - 0 7");
        g.playSan("Kd8");
        g.playSan("Kd2");
        assertEquals("7... Kd8 8. Kd2", g.moveText());
    }

    @Test
    void uciAndSanAgree() {
        Game a = new Game();
        Game b = new Game();
        a.playUci("g1f3");
        b.playSan("Nf3");
        assertEquals(a.position().toFen(), b.position().toFen());
        assertEquals(1, a.history().size());
        assertEquals("Nf3", a.history().get(0));
    }
}
