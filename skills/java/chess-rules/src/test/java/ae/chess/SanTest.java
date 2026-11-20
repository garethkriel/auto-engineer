package ae.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SanTest {

    @ParameterizedTest(name = "{1} in {0}")
    @CsvSource(delimiter = '|', value = {
        PerftTest.START + "|e2e4|e4",
        PerftTest.START + "|g1f3|Nf3",
        "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2|e4d5|exd5",
        "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2|d8h4|Qh4#",
        "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1|e5d6|exd6",
        "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1|e1g1|O-O",
        "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1|e1c1|O-O-O",
        "r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1|e8g8|O-O",
        "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1|a1a8|Ra8#",
        "4k3/P7/8/8/8/8/8/4K3 w - - 0 1|a7a8q|a8=Q+",
        "4k3/P7/8/8/8/8/8/4K3 w - - 0 1|a7a8n|a8=N",
        "1n2k3/P7/8/8/8/8/8/4K3 w - - 0 1|a7b8r|axb8=R+",
    })
    void knownNotation(String fen, String uci, String san) {
        Position p = Position.fromFen(fen);
        Move m = Move.of(uci);
        assertEquals(san, San.toSan(p, m));
        assertEquals(m, San.parse(p, san));
        assertEquals(fen, p.toFen(), "toSan must leave the position as it found it");
    }

    @Test
    void disambiguatesByFile() {
        Position p = Position.fromFen("4k3/8/8/8/8/8/4K3/R6R w - - 0 1");
        assertEquals("Rad1", San.toSan(p, Move.of("a1d1")));
        assertEquals("Rhd1", San.toSan(p, Move.of("h1d1")));
        assertEquals(Move.of("h1d1"), San.parse(p, "Rhd1"));
    }

    @Test
    void disambiguatesByRankWhenFilesMatch() {
        Position p = Position.fromFen("4k3/R7/8/8/8/8/R7/4K3 w - - 0 1");
        assertEquals("R7a4", San.toSan(p, Move.of("a7a4")));
        assertEquals("R2a4", San.toSan(p, Move.of("a2a4")));
    }

    @Test
    void threeQueensNeedFileRankAndNothing() {
        // c3 is reachable from a1 (shares file with a5), a5 (shares file with a1 and rank with e5), e5.
        Position p = Position.fromFen("6k1/8/8/Q3Q3/8/8/8/Q6K w - - 0 1");
        assertEquals("Q1c3", San.toSan(p, Move.of("a1c3")));
        assertEquals("Qa5c3", San.toSan(p, Move.of("a5c3")));
        assertEquals("Qec3", San.toSan(p, Move.of("e5c3")));
        assertEquals(Move.of("a5c3"), San.parse(p, "Qa5c3"));
    }

    @Test
    void annotationsAreIgnoredWhenParsing() {
        Position p = Position.fromFen(PerftTest.START);
        assertEquals(Move.of("e2e4"), San.parse(p, "e4!?"));
        assertEquals(Move.of("g1f3"), San.parse(p, "Nf3+"));
    }

    @Test
    void rejectsIllegalAndAmbiguousText() {
        Position p = Position.fromFen("4k3/8/8/8/8/8/4K3/R6R w - - 0 1");
        String m1 = assertThrows(IllegalArgumentException.class, () -> San.parse(p, "Rd1")).getMessage();
        assertTrue(m1.contains("Rd1"), m1);
        assertThrows(IllegalArgumentException.class, () -> San.parse(Position.fromFen(PerftTest.START), "e5"));
        assertThrows(IllegalArgumentException.class, () -> San.parse(Position.fromFen(PerftTest.START), ""));
        assertThrows(IllegalArgumentException.class, () -> San.parse(Position.fromFen(PerftTest.START), "Ke2"));
    }

    @Test
    void everyLegalMoveRoundTripsAndIsUniqueInRandomPositions() {
        Random rnd = new Random(77);
        int checked = 0;
        for (int game = 0; game < 30; game++) {
            Position p = Position.fromFen(InvariantTest.FENS[game % InvariantTest.FENS.length]);
            for (int ply = 0; ply < 60; ply++) {
                var moves = p.legalMoves();
                if (moves.isEmpty()) {
                    break;
                }
                Set<String> sans = new HashSet<>();
                for (Move m : moves) {
                    String san = San.toSan(p, m);
                    assertTrue(sans.add(san), "duplicate SAN " + san + " in " + p.toFen());
                    assertEquals(m, San.parse(p, san), san + " in " + p.toFen());
                    checked++;
                }
                p.make(moves.get(rnd.nextInt(moves.size())));
            }
        }
        assertTrue(checked > 3000, "checked only " + checked);
    }
}
