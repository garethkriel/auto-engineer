package ae.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FenTest {

    @ParameterizedTest
    @ValueSource(strings = {
        PerftTest.START, PerftTest.KIWI, PerftTest.POS3, PerftTest.POS4, PerftTest.POS5, PerftTest.POS6,
        "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
        "4k3/8/8/8/8/8/8/4K3 b - - 37 90",
    })
    void roundTrip(String fen) {
        assertEquals(fen, Position.fromFen(fen).toFen());
    }

    @Test
    void fourFieldFenGetsDefaultClocks() {
        assertEquals("8/8/8/8/8/8/8/K6k w - - 0 1", Position.fromFen("8/8/8/8/8/8/8/K6k w - -").toFen());
    }

    @Test
    void startPositionBasics() {
        Position p = Position.fromFen(Position.START_FEN);
        assertTrue(p.whiteToMove());
        assertEquals('R', p.pieceAt(0));
        assertEquals('k', p.pieceAt(60));
        assertEquals(' ', p.pieceAt(28));
        assertEquals(20, p.legalMoves().size());
        assertFalse(p.inCheck());
    }

    private static String message(String fen) {
        return assertThrows(IllegalArgumentException.class, () -> Position.fromFen(fen), fen).getMessage();
    }

    @Test
    void rejectsMalformedFens() {
        String ok = "4k3/8/8/8/8/8/8/4K3 w - - 0 1";
        assertEquals(ok, Position.fromFen(ok).toFen()); // control: the base string loads
        assertTrue(message("4k3/8/8/8/8/8/8/4K3 w - -  0").contains("fields"));
        assertTrue(message("4k3/8/8/8/8/8/4K3 w - - 0 1").contains("ranks"));
        assertTrue(message("4k3/8/8/8/8/8/8/4K4 w - - 0 1").contains("rank"));
        assertTrue(message("4k3/8/8/8/8/8/8/4X3 w - - 0 1").contains("character"));
        assertTrue(message("4k3/8/8/8/8/8/8/4K3 x - - 0 1").contains("side"));
        assertTrue(message("4k3/8/8/8/8/8/8/4K3 w - - -1 1").contains("clock"));
        assertTrue(message("4k3/8/8/8/8/8/8/4K3 w - - 0 0").contains("move number"));
    }

    @Test
    void rejectsKingCountAndPawnsOnBackRank() {
        assertTrue(message("8/8/8/8/8/8/8/4K3 w - - 0 1").contains("king"));
        assertTrue(message("4k3/8/8/8/8/8/8/3KK3 w - - 0 1").contains("king"));
        assertTrue(message("P3k3/8/8/8/8/8/8/4K3 w - - 0 1").contains("pawn"));
        assertTrue(message("4k3/8/8/8/8/8/8/p3K3 w - - 0 1").contains("pawn"));
    }

    @Test
    void sideNotToMoveCannotBeInCheck() {
        // white to move while black king is attacked by the rook on e1
        assertTrue(message("4k3/8/8/8/8/8/8/K3R3 w - - 0 1").contains("check"));
    }

    @Test
    void castlingRightsNeedKingAndRook() {
        assertTrue(message("r3k3/8/8/8/8/8/8/R3K3 w KQkq - 0 1").contains("castling"));
        assertTrue(message("4k3/8/8/8/8/8/8/R3K3 w K - 0 1").contains("castling"));
        assertTrue(message("4k3/8/8/8/8/8/8/R3K3 w KK - 0 1").contains("castling"));
        assertEquals("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1",
                Position.fromFen("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1").toFen());
    }

    @Test
    void epSquareMustMatchSideToMove() {
        String base = "4k3/8/8/8/4Pp2/8/8/4K3 b - %s 0 1";
        assertEquals(String.format(base, "e3"), Position.fromFen(String.format(base, "e3")).toFen());
        assertTrue(message(String.format(base, "e6")).contains("en passant"));
        assertTrue(message(String.format(base, "z3")).contains("en passant"));
    }
}
