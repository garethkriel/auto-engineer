package ae.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Published perft node counts (chessprogramming wiki "Perft Results"). */
class PerftTest {

    static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    static final String KIWI = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
    static final String POS3 = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1";
    static final String POS4 = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1";
    static final String POS5 = "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8";
    static final String POS6 =
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10";

    @ParameterizedTest(name = "{0} depth {1} = {2}")
    @CsvSource(delimiter = '|', value = {
        START + "|1|20",
        START + "|2|400",
        START + "|3|8902",
        START + "|4|197281",
        KIWI + "|1|48",
        KIWI + "|2|2039",
        KIWI + "|3|97862",
        POS3 + "|1|14",
        POS3 + "|2|191",
        POS3 + "|3|2812",
        POS3 + "|4|43238",
        POS3 + "|5|674624",
        POS4 + "|1|6",
        POS4 + "|2|264",
        POS4 + "|3|9467",
        POS4 + "|4|422333",
        POS5 + "|1|44",
        POS5 + "|2|1486",
        POS5 + "|3|62379",
        POS6 + "|1|46",
        POS6 + "|2|2079",
        POS6 + "|3|89890",
    })
    void publishedNodeCounts(String fen, int depth, long nodes) {
        assertEquals(nodes, Perft.count(Position.fromFen(fen), depth));
    }

    @ParameterizedTest(name = "mirrored {0} depth {1} = {2}")
    @CsvSource(delimiter = '|', value = {
        KIWI + "|3|97862",
        POS3 + "|4|43238",
        POS4 + "|3|9467",
        POS5 + "|3|62379",
        POS6 + "|3|89890",
    })
    void mirroredPositionsHaveTheSameCounts(String fen, int depth, long nodes) {
        String mirrored = TestFens.mirror(fen);
        assertEquals(nodes, Perft.count(Position.fromFen(mirrored), depth), mirrored);
    }

    @ParameterizedTest(name = "divide of {0} sums to perft")
    @CsvSource(delimiter = '|', value = {KIWI + "|3", POS4 + "|3"})
    void divideSumsToTotal(String fen, int depth) {
        Position p = Position.fromFen(fen);
        long sum = 0;
        for (var e : Perft.divide(p, depth).entrySet()) {
            sum += e.getValue();
        }
        assertEquals(Perft.count(p, depth), sum);
        assertEquals(Position.fromFen(fen).toFen(), p.toFen(), "divide must leave the position untouched");
    }

    @org.junit.jupiter.api.Test
    void depthZeroIsOneNode() {
        assertEquals(1, Perft.count(Position.fromFen(START), 0));
    }
}
