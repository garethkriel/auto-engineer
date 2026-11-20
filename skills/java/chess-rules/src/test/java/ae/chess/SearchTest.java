package ae.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SearchTest {

    @Test
    void foolsMateIsMateInOne() {
        Position p = Position.fromFen("rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2");
        Optional<Search.Mate> mate = Search.shortestMate(p, 3);
        assertTrue(mate.isPresent());
        assertEquals(Move.of("d8h4"), mate.get().move());
        assertEquals(1, mate.get().moves());
    }

    @Test
    void backRankMateInOne() {
        Position p = Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1");
        Search.Mate mate = Search.shortestMate(p, 2).orElseThrow();
        assertEquals(Move.of("a1a8"), mate.move());
        assertEquals(1, mate.moves());
    }

    @Test
    void ladderMateInTwo() {
        Position p = Position.fromFen("7k/8/8/8/8/8/R7/1R5K w - - 0 1");
        assertTrue(Search.shortestMate(p, 1).isEmpty(), "no mate in one exists");
        Search.Mate mate = Search.shortestMate(p, 2).orElseThrow();
        assertEquals(2, mate.moves());
        // Hand-checked line: 1.Ra7 leaves only Kg8, then 2.Rb8#.
        Position q = Position.fromFen(p.toFen());
        q.make(Move.of("a2a7"));
        assertEquals(List.of("h8g8"), q.legalMoves().stream().map(Move::toString).toList());
        q.make(Move.of("h8g8"));
        q.make(Move.of("b1b8"));
        assertTrue(q.isCheckmate());
    }

    @Test
    void reportedMateSurvivesEveryDefence() {
        Position p = Position.fromFen("7k/8/8/8/8/8/R7/1R5K w - - 0 1");
        Search.Mate mate = Search.shortestMate(p, 2).orElseThrow();
        p.make(mate.move());
        assertTrue(p.legalMoves().size() > 0);
        for (Move reply : p.legalMoves()) {
            Position q = p.copy();
            q.make(reply);
            assertTrue(Search.shortestMate(q, 1).isPresent(), "no mate after " + reply);
        }
    }

    @Test
    void noForcedMateFromTheStartPosition() {
        assertTrue(Search.shortestMate(Position.fromFen(PerftTest.START), 2).isEmpty());
    }

    @Test
    void stalematedPositionsAreNotMates() {
        // Qg6 stalemates (no mate); the solver must find the real mate Qg7# instead.
        Position p = Position.fromFen("7k/8/5K2/8/8/8/8/6Q1 w - - 0 1");
        Search.Mate mate = Search.shortestMate(p, 1).orElseThrow();
        assertEquals(Move.of("g1g7"), mate.move());
        Position q = Position.fromFen(p.toFen());
        q.make(Move.of("g1g6"));
        assertTrue(q.isStalemate());
        assertEquals(0, Search.alphaBeta(q, 3).score());
    }

    @Test
    void winsAHangingQueen() {
        Position p = Position.fromFen("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1");
        for (int depth = 1; depth <= 3; depth++) {
            assertEquals(Move.of("d1d5"), Search.alphaBeta(p, depth).move(), "depth " + depth);
        }
        assertEquals(500, Search.alphaBeta(p, 1).score());
    }

    @Test
    void mateScoresPreferFasterMates() {
        Position p = Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1");
        assertEquals(Search.MATE - 1, Search.alphaBeta(p, 2).score());
        assertEquals(Search.MATE - 1, Search.negamax(p, 2));
    }

    @Test
    void alphaBetaAgreesWithPlainNegamax() {
        String[] fens = {
            PerftTest.START, PerftTest.KIWI, PerftTest.POS3, PerftTest.POS4, PerftTest.POS5,
            "4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1",
            "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
        };
        for (String fen : fens) {
            for (int depth = 1; depth <= 3; depth++) {
                Position a = Position.fromFen(fen);
                Position b = Position.fromFen(fen);
                assertEquals(Search.negamax(a, depth), Search.alphaBeta(b, depth).score(),
                        fen + " depth " + depth);
                assertEquals(fen, b.toFen());
            }
        }
    }

    @Test
    void searchingAFinishedGameReturnsNoMove() {
        Position mated = Position.fromFen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3");
        Search.Best best = Search.alphaBeta(mated, 2);
        assertEquals(null, best.move());
        assertEquals(-Search.MATE, best.score());
    }
}
