package ae.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InvariantTest {

    static final String[] FENS = {
        PerftTest.START, PerftTest.KIWI, PerftTest.POS3, PerftTest.POS4, PerftTest.POS5, PerftTest.POS6,
    };

    private static int count(String fen, char c) {
        int n = 0;
        for (char x : fen.split(" ")[0].toCharArray()) {
            if (x == c) {
                n++;
            }
        }
        return n;
    }

    @Test
    void randomPlayoutsKeepEveryInvariant() {
        Random rnd = new Random(20261120);
        int positions = 0;
        int castles = 0;
        int eps = 0;
        int promos = 0;
        for (int game = 0; game < 60; game++) {
            Position p = Position.fromFen(FENS[game % FENS.length]);
            for (int ply = 0; ply < 120; ply++) {
                List<Move> moves = p.legalMoves();
                if (moves.isEmpty()) {
                    break;
                }
                String before = p.toFen();
                Set<String> seen = new HashSet<>();
                for (Move m : moves) {
                    assertTrue(seen.add(m.toString()), "duplicate move " + m + " in " + before);
                    boolean mover = p.whiteToMove();
                    Undo u = p.make(m);
                    assertFalse(p.isAttacked(p.kingSquare(mover), !mover), m + " leaves king en prise: " + before);
                    String after = p.toFen();
                    assertEquals(1, count(after, 'K'), after);
                    assertEquals(1, count(after, 'k'), after);
                    p.unmake(m, u);
                    assertEquals(before, p.toFen(), "unmake of " + m);
                }
                Move pick = moves.get(rnd.nextInt(moves.size()));
                for (Move m : moves) { // prefer special moves half of the time so they get exercised
                    char mp = Character.toUpperCase(p.pieceAt(m.from()));
                    boolean special = m.promo() != 0
                            || (mp == 'K' && Math.abs(m.to() - m.from()) == 2)
                            || (mp == 'P' && p.pieceAt(m.to()) == ' ' && m.to() % 8 != m.from() % 8);
                    if (special && rnd.nextBoolean()) {
                        pick = m;
                        break;
                    }
                }
                char piece = p.pieceAt(pick.from());
                if (Character.toUpperCase(piece) == 'K' && Math.abs(pick.to() - pick.from()) == 2) {
                    castles++;
                }
                if (Character.toUpperCase(piece) == 'P' && p.pieceAt(pick.to()) == ' '
                        && pick.to() % 8 != pick.from() % 8) {
                    eps++;
                }
                if (pick.promo() != 0) {
                    promos++;
                }
                p.make(pick);
                positions++;
                Position reparsed = Position.fromFen(p.toFen());
                assertEquals(p.toFen(), reparsed.toFen());
                assertEquals(p.legalMoves().size(), reparsed.legalMoves().size());
            }
        }
        assertTrue(positions > 3000, "playouts too short: " + positions);
        assertTrue(castles > 0 && eps > 0 && promos > 0,
                "special moves never exercised: " + castles + "/" + eps + "/" + promos);
    }

    @Test
    void copyIsIndependent() {
        Position a = Position.fromFen(PerftTest.KIWI);
        Position b = a.copy();
        b.make(b.legalMoves().get(0));
        assertEquals(PerftTest.KIWI, a.toFen());
        assertNotEquals(a.toFen(), b.toFen());
    }

    @Test
    void repetitionKeyIgnoresUncapturableEpButNotCapturableEp() {
        String plain = Position.fromFen("4k3/8/8/8/4P3/8/8/4K3 b - - 0 1").repetitionKey();
        String uncapturable = Position.fromFen("4k3/8/8/8/4P3/8/8/4K3 b - e3 0 1").repetitionKey();
        assertEquals(plain, uncapturable);
        String withPawn = Position.fromFen("4k3/8/8/8/3pP3/8/8/4K3 b - - 0 1").repetitionKey();
        String capturable = Position.fromFen("4k3/8/8/8/3pP3/8/8/4K3 b - e3 0 1").repetitionKey();
        assertNotEquals(withPawn, capturable);
    }

    @Test
    void clocksAdvance() {
        Position p = Position.fromFen("4k3/8/8/8/8/8/8/R3K3 w - - 5 10");
        p.make(Move.of("a1a2"));
        assertEquals("4k3/8/8/8/8/8/R7/4K3 b - - 6 10", p.toFen());
        p.make(Move.of("e8e7"));
        assertEquals("8/4k3/8/8/8/8/R7/4K3 w - - 7 11", p.toFen());
    }
}
