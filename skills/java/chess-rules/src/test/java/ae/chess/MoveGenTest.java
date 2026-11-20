package ae.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoveGenTest {

    private static Set<String> uci(Position p) {
        Set<String> out = new TreeSet<>();
        for (Move m : p.legalMoves()) {
            out.add(m.toString());
        }
        return out;
    }

    @Test
    void bothCastlesAndTwentySixMoves() {
        Position p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        Set<String> moves = uci(p);
        assertEquals(26, moves.size()); // king 5 + castles 2 + a1 rook 10 + h1 rook 9
        assertTrue(moves.contains("e1g1"));
        assertTrue(moves.contains("e1c1"));
    }

    @Test
    void cannotCastleThroughAnAttackedSquare() {
        Set<String> moves = uci(Position.fromFen("4k3/8/8/8/8/8/5r2/R3K2R w KQ - 0 1"));
        assertFalse(moves.contains("e1g1"), "f1 is attacked by the rook on f2");
        assertTrue(moves.contains("e1c1"));
    }

    @Test
    void cannotCastleOutOfCheck() {
        Set<String> moves = uci(Position.fromFen("4k3/8/8/8/8/8/4r3/R3K2R w KQ - 0 1"));
        assertFalse(moves.contains("e1g1"));
        assertFalse(moves.contains("e1c1"));
        assertTrue(moves.contains("e1e2"), "king may capture the unprotected checker");
    }

    @Test
    void rookMayPassThroughAnAttackedSquareOnQueenside() {
        Set<String> moves = uci(Position.fromFen("4k3/8/8/8/8/8/1r6/R3K3 w Q - 0 1"));
        assertTrue(moves.contains("e1c1"), "b1 is attacked but only the rook crosses it");
    }

    @Test
    void cannotCastleWhenBlocked() {
        Set<String> moves = uci(Position.fromFen("4k3/8/8/8/8/8/8/RN2K1NR w KQ - 0 1"));
        assertFalse(moves.contains("e1g1"));
        assertFalse(moves.contains("e1c1"));
    }

    @Test
    void castlingMovesTheRookAndClearsRights() {
        Position p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        Undo u = p.make(Move.of("e1g1"));
        assertEquals("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1", p.toFen());
        p.unmake(Move.of("e1g1"), u);
        assertEquals("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", p.toFen());
        Undo q = p.make(Move.of("e1c1"));
        assertEquals("r3k2r/8/8/8/8/8/8/2KR3R b kq - 1 1", p.toFen());
        p.unmake(Move.of("e1c1"), q);
        assertEquals("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", p.toFen());
    }

    @Test
    void capturingARookOnItsHomeSquareRemovesTheRight() {
        Position p = Position.fromFen("r3k2r/8/8/8/8/8/6B1/R3K2R w KQkq - 0 1");
        Undo u = p.make(Move.of("g2a8"));
        assertEquals("B3k2r/8/8/8/8/8/8/R3K2R b KQk - 0 1", p.toFen());
        p.unmake(Move.of("g2a8"), u);
        assertEquals("r3k2r/8/8/8/8/8/6B1/R3K2R w KQkq - 0 1", p.toFen());
    }

    @Test
    void enPassantCapturesTheRightPawnAndUndoes() {
        String fen = "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1";
        Position p = Position.fromFen(fen);
        assertTrue(uci(p).contains("e5d6"));
        Undo u = p.make(Move.of("e5d6"));
        assertEquals("4k3/8/3P4/8/8/8/8/4K3 b - - 0 1", p.toFen());
        p.unmake(Move.of("e5d6"), u);
        assertEquals(fen, p.toFen());
    }

    @Test
    void epSquareWithoutAPawnToCaptureGeneratesNoCapture() {
        // The FEN claims e6 is an ep target but there is no black pawn on e5: d5xe6 would capture nothing.
        Set<String> moves = uci(Position.fromFen("4k3/8/8/3P4/8/8/8/4K3 w - e6 0 1"));
        assertFalse(moves.contains("d5e6"));
        assertTrue(moves.contains("d5d6"));
    }

    @Test
    void doublePushSetsEpSquareAndClockResets() {
        Position p = Position.fromFen("4k3/8/8/8/8/8/4P3/4K3 w - - 7 9");
        p.make(Move.of("e2e4"));
        assertEquals("4k3/8/8/8/4P3/8/8/4K3 b - e3 0 9", p.toFen());
    }

    @Test
    void enPassantThatExposesTheKingAlongTheRankIsIllegal() {
        Set<String> moves = uci(Position.fromFen("8/8/8/KPp4r/8/8/8/6k1 w - c6 0 1"));
        assertFalse(moves.contains("b5c6"), "removing both pawns opens the fifth rank to the rook");
        assertTrue(moves.contains("b5b6"));
    }

    @Test
    void promotionsOfferFourPieces() {
        Set<String> moves = uci(Position.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1"));
        assertTrue(moves.containsAll(List.of("a7a8q", "a7a8r", "a7a8b", "a7a8n")));
        assertFalse(moves.contains("a7a8"));
        assertEquals(9, moves.size());
    }

    @Test
    void promotionCaptureAndUndo() {
        String fen = "1n2k3/P7/8/8/8/8/8/4K3 w - - 0 1";
        Position p = Position.fromFen(fen);
        Move m = Move.of("a7b8q");
        assertTrue(uci(p).contains("a7b8q"));
        Undo u = p.make(m);
        assertEquals("1Q2k3/8/8/8/8/8/8/4K3 b - - 0 1", p.toFen());
        p.unmake(m, u);
        assertEquals(fen, p.toFen());
    }

    @Test
    void blackPromotesDownTheBoard() {
        Set<String> moves = uci(Position.fromFen("4k3/8/8/8/8/8/p7/4K3 b - - 0 1"));
        assertTrue(moves.containsAll(List.of("a2a1q", "a2a1r", "a2a1b", "a2a1n")));
    }

    @Test
    void pinnedPieceCannotLeaveTheLine() {
        // Knight e2 is pinned by the rook on e7: only the four king steps remain.
        assertEquals(Set.of("e1d1", "e1d2", "e1f1", "e1f2"),
                uci(Position.fromFen("4k3/4r3/8/8/8/8/4N3/4K3 w - - 0 1")));
    }

    @Test
    void checkMustBeAnswered() {
        // Rook e8 checks the king on e1 (e2 stays on the rook's file, d2 holds our bishop).
        Position p = Position.fromFen("4r2k/8/8/8/8/8/3B4/4K3 w - - 0 1");
        assertTrue(p.inCheck());
        assertEquals(Set.of("e1d1", "e1f1", "e1f2", "d2e3"), uci(p));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3|true|false",
        "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1|false|true",
        "4k3/8/8/8/8/8/8/4K3 w - - 0 1|false|false",
    })
    void mateAndStalemate(String fen, boolean mate, boolean stale) {
        Position p = Position.fromFen(fen);
        assertEquals(mate, p.isCheckmate(), fen);
        assertEquals(stale, p.isStalemate(), fen);
        if (mate || stale) {
            assertTrue(p.legalMoves().isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "8/8/8/8/8/8/8/K6k w - - 0 1|true",
        "8/8/8/8/8/8/8/KB5k w - - 0 1|true",
        "8/8/8/8/8/8/8/KN5k w - - 0 1|true",
        "2b5/8/8/8/8/8/8/2B1K2k w - - 0 1|false",
        "5b2/8/8/8/8/8/8/2B1K2k w - - 0 1|true",
        "8/8/8/8/8/8/8/KNN4k w - - 0 1|false",
        "8/8/8/8/8/8/P7/K6k w - - 0 1|false",
        "8/8/8/8/8/7k/8/KR6 w - - 0 1|false",
    })
    void insufficientMaterial(String fen, boolean expected) {
        assertEquals(expected, Position.fromFen(fen).insufficientMaterial(), fen);
    }

    @Test
    void attackQueries() {
        Position p = Position.fromFen(PerftTest.START);
        assertTrue(p.isAttacked(Move.square("e3"), true));
        assertFalse(p.isAttacked(Move.square("e4"), true));
        assertTrue(p.isAttacked(Move.square("f6"), false));
        assertTrue(p.isAttacked(Move.square("e2"), true), "protected pawn counts as attacked");
    }

    @Test
    void moveTextRoundTrip() {
        assertEquals("e7e8q", Move.of("e7e8q").toString());
        assertEquals(Move.square("h8"), 63);
        assertEquals("a1", Move.squareName(0));
    }
}
