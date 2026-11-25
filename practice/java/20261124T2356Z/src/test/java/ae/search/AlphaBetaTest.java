package ae.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class AlphaBetaTest {
    private static final int WIN = AlphaBeta.WIN;

    /** Random position reached by playing at most {@code maxPlies} random moves from root. */
    private static Position randomPosition(Position root, SplittableRandom rnd, int maxPlies) {
        Position p = root;
        int plies = rnd.nextInt(maxPlies + 1);
        for (int i = 0; i < plies; i++) {
            int[] ms = p.moves();
            if (ms.length == 0) {
                break;
            }
            p = p.play(ms[rnd.nextInt(ms.length)]);
        }
        return p;
    }

    private static List<Position> roots() {
        List<Position> r = new ArrayList<>();
        r.add(ConnectK.ticTacToe());
        r.add(ConnectK.start(4, 4, 3, true));
        r.add(ConnectK.start(5, 4, 4, true));
        r.add(ConnectK.start(3, 4, 3, false));
        return r;
    }

    @Test
    void withoutTableMatchesMinimaxAtEveryDepth() {
        SplittableRandom rnd = new SplittableRandom(11);
        for (Position root : roots()) {
            for (int i = 0; i < 25; i++) {
                Position p = randomPosition(root, rnd, 10);
                if (p.status() != Position.Status.ONGOING) {
                    continue;
                }
                for (int depth = 1; depth <= 4; depth++) {
                    int expected = Minimax.value(p, depth);
                    for (boolean ordering : new boolean[] {false, true}) {
                        AlphaBeta ab = new AlphaBeta(0, ordering);
                        assertEquals(expected, ab.search(p, depth).score(),
                            "depth " + depth + " ordering " + ordering + " ply " + p.ply());
                    }
                }
            }
        }
    }

    /** Plays random moves until at most {@code left} plies remain before the board is full. */
    private static Position nearEnd(Position root, SplittableRandom rnd, int left) {
        Position p = root;
        while (((ConnectK) p).capacity() - p.ply() > left && p.status() == Position.Status.ONGOING) {
            int[] ms = p.moves();
            p = p.play(ms[rnd.nextInt(ms.length)]);
        }
        return p;
    }

    private static int remaining(Position p) {
        return ((ConnectK) p).capacity() - p.ply();
    }

    @Test
    void withTableMatchesMinimaxWhenSearchingToTheEnd() {
        SplittableRandom rnd = new SplittableRandom(12);
        int[] sizes = {1, 7, 1024, 200_003};
        int checked = 0;
        for (Position root : roots()) {
            for (int i = 0; i < 20; i++) {
                Position p = nearEnd(root, rnd, 9);
                if (p.status() != Position.Status.ONGOING) {
                    continue;
                }
                int expected = Minimax.value(p, remaining(p));
                for (int size : sizes) {
                    for (boolean ordering : new boolean[] {false, true}) {
                        AlphaBeta ab = new AlphaBeta(size, ordering);
                        assertEquals(expected, ab.search(p, remaining(p)).score(),
                            "table " + size + " ordering " + ordering + " remaining " + remaining(p));
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked >= 200, "enough ongoing positions were generated: " + checked);
    }

    @Test
    void oneTableReusedAcrossManySearchesStaysExact() {
        SplittableRandom rnd = new SplittableRandom(13);
        Position root = ConnectK.start(4, 4, 3, true);
        AlphaBeta shared = new AlphaBeta(64, true);
        int checked = 0;
        for (int i = 0; i < 120; i++) {
            Position p = nearEnd(root, rnd, 8);
            if (p.status() != Position.Status.ONGOING) {
                continue;
            }
            assertEquals(Minimax.value(p, remaining(p)), shared.search(p, remaining(p)).score());
            checked++;
        }
        assertTrue(checked >= 40, "enough ongoing positions were generated: " + checked);
    }

    @Test
    void searchingTheSamePositionTwiceStillReturnsAMove() {
        AlphaBeta ab = new AlphaBeta(4096, true);
        Position p = ConnectK.ticTacToe().play(4);
        AlphaBeta.Result first = ab.search(p, 8);
        AlphaBeta.Result second = ab.search(p, 8);
        assertEquals(first.score(), second.score());
        assertNotEquals(AlphaBeta.NO_MOVE, second.move(), "the root must not be cut off by its own table entry");
        assertTrue(second.nodes() < first.nodes(), "second search reuses the table: " + second.nodes() + " vs " + first.nodes());
    }

    @Test
    void bestMoveAchievesTheScore() {
        SplittableRandom rnd = new SplittableRandom(14);
        for (Position root : roots()) {
            for (int i = 0; i < 20; i++) {
                Position p = nearEnd(root, rnd, 9);
                if (p.status() != Position.Status.ONGOING) {
                    continue;
                }
                for (int size : new int[] {0, 512}) {
                    AlphaBeta.Result r = new AlphaBeta(size, true).search(p, remaining(p));
                    assertTrue(contains(p.moves(), r.move()), "move is legal");
                    assertEquals(r.score(), -Minimax.value(p.play(r.move()), remaining(p) - 1),
                        "table " + size + ": the chosen move must be worth the reported score");
                }
            }
        }
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    @Test
    void analyseGivesTheExactValueOfEveryMove() {
        SplittableRandom rnd = new SplittableRandom(15);
        for (Position root : roots()) {
            for (int i = 0; i < 12; i++) {
                Position p = nearEnd(root, rnd, 8);
                if (p.status() != Position.Status.ONGOING) {
                    continue;
                }
                int[] moves = p.moves();
                for (int size : new int[] {0, 256}) {
                    int[] scores = new AlphaBeta(size, true).analyse(p, remaining(p));
                    assertEquals(moves.length, scores.length);
                    int best = Integer.MIN_VALUE;
                    for (int k = 0; k < moves.length; k++) {
                        assertEquals(-Minimax.value(p.play(moves[k]), remaining(p) - 1), scores[k],
                            "move " + moves[k] + " table " + size);
                        best = Math.max(best, scores[k]);
                    }
                    assertEquals(Minimax.value(p, remaining(p)), best);
                }
            }
        }
    }

    @Test
    void ticTacToeIsADrawUnderEveryConfiguration() {
        for (int size : new int[] {0, 1, 100, 100_000}) {
            for (boolean ordering : new boolean[] {false, true}) {
                AlphaBeta.Result r = new AlphaBeta(size, ordering).search(ConnectK.ticTacToe(), 9);
                assertEquals(0, r.score(), "table " + size + " ordering " + ordering);
            }
        }
    }

    @Test
    void everyFirstMoveOfTicTacToeDraws() {
        int[] scores = new AlphaBeta(0, true).analyse(ConnectK.ticTacToe(), 9);
        assertArrayEquals(new int[9], scores, "perfect play from any first move draws");
    }

    @Test
    void pruningVisitsFewerNodesThanMinimax() {
        Position root = ConnectK.ticTacToe();
        long minimax = Perft.nodes(root);
        long plain = new AlphaBeta(0, false).search(root, 9).nodes();
        long ordered = new AlphaBeta(0, true).search(root, 9).nodes();
        long table = new AlphaBeta(1 << 16, false).search(root, 9).nodes();
        long both = new AlphaBeta(1 << 16, true).search(root, 9).nodes();
        assertEquals(549946L, minimax);
        assertTrue(plain < minimax / 2, "alpha-beta alone: " + plain);
        assertTrue(ordered <= plain, "ordering must not hurt: " + ordered + " vs " + plain);
        assertTrue(table < plain, "table must help: " + table + " vs " + plain);
        assertTrue(both < table, "table plus ordering is best: " + both + " vs " + table);
    }

    @Test
    void rootWindowNarrowsAfterTheFirstMove() {
        // analyse() searches every root move with a full window; search() passes the best score so far down,
        // so it must visit strictly fewer nodes while reporting the same value.
        Position root = ConnectK.ticTacToe();
        AlphaBeta ab = new AlphaBeta(0, false);
        int best = ab.search(root, 9).score();
        long narrow = ab.nodes();
        int[] all = ab.analyse(root, 9);
        long full = ab.nodes();
        assertEquals(best, java.util.Arrays.stream(all).max().getAsInt());
        assertTrue(narrow < full, "narrow " + narrow + " vs full " + full);
    }

    @Test
    void deepeningThroughOneTableMatchesMinimaxAtEveryDepth() {
        // The same table serves depth 1, 2, 3, 4 in turn: entries from a shallower search must never answer a
        // deeper one, so each result equals plain minimax at its own depth.
        SplittableRandom rnd = new SplittableRandom(17);
        int checked = 0;
        for (Position root : roots()) {
            for (int i = 0; i < 8; i++) {
                Position p = randomPosition(root, rnd, 6);
                if (p.status() != Position.Status.ONGOING) {
                    continue;
                }
                AlphaBeta ab = new AlphaBeta(1 << 14, true);
                for (int d = 1; d <= 4; d++) {
                    assertEquals(Minimax.value(p, d), ab.search(p, d).score(), "depth " + d + " ply " + p.ply());
                    checked++;
                }
            }
        }
        assertTrue(checked >= 60, "checked " + checked);
    }

    @Test
    void theOnlyWinningMoveIsFound() {
        // X=3 (cells 0,1,6), O=2 (cells 3,4): ply 5, O to move. O wins at cell 5 (ply 6);
        // X threatens cell 2, so if O did anything else X would win.
        Position p = ConnectK.parse(3, false,
            "XX.",
            "OO.",
            "X..");
        assertEquals(5, p.ply());
        AlphaBeta.Result r = new AlphaBeta(0, true).search(p, 4);
        assertEquals(5, r.move(), "O completes the middle row at cell 5");
        assertEquals(WIN - 6, r.score());
    }

    @Test
    void forcedLossGetsTheSlowestLoss() {
        // X to move (ply 4) with O threatening column 1: every X move except 7 loses at ply 6.
        Position p = ConnectK.parse(3, false,
            "XOX",
            ".O.",
            "...");
        AlphaBeta.Result r = new AlphaBeta(0, true).search(p, 3);
        assertEquals(7, r.move(), "blocking is the only move that does not lose within the horizon");
        assertTrue(r.score() > -WIN / 2);
        int[] all = new AlphaBeta(0, false).analyse(p, 3);
        int[] moves = p.moves();
        for (int k = 0; k < moves.length; k++) {
            if (moves[k] != 7) {
                assertEquals(-(WIN - 6), all[k], "move " + moves[k]);
            }
        }
    }

    @Test
    void iterativeDeepeningStopsAtAProvenWin() {
        Position p = ConnectK.parse(3, false,
            "XX.",
            "OO.",
            "...");
        AlphaBeta.Result r = new AlphaBeta(0, true).iterate(p, 9);
        assertEquals(1, r.depth(), "a win in one is proven at depth 1");
        assertEquals(WIN - 5, r.score());
        assertEquals(2, r.move());
    }

    @Test
    void iterativeDeepeningReachesTheLimitWhenNothingIsProven() {
        Position root = ConnectK.start(4, 4, 4, true);
        AlphaBeta.Result r = new AlphaBeta(0, true).iterate(root, 3);
        assertEquals(3, r.depth());
        assertEquals(Minimax.value(root, 3), r.score());
    }

    @Test
    void iterativeDeepeningWithoutTableEqualsMinimaxAtTheReachedDepth() {
        SplittableRandom rnd = new SplittableRandom(16);
        for (Position root : roots()) {
            for (int i = 0; i < 10; i++) {
                Position p = randomPosition(root, rnd, 8);
                if (p.status() != Position.Status.ONGOING) {
                    continue;
                }
                AlphaBeta.Result r = new AlphaBeta(0, true).iterate(p, 4);
                if (Math.abs(r.score()) <= WIN / 2) {
                    assertEquals(4, r.depth());
                } else {
                    assertTrue(r.depth() <= 4);
                }
                assertEquals(Minimax.value(p, r.depth()), r.score());
            }
        }
    }

    @Test
    void terminalRootReturnsItsScoreAndNoMove() {
        Position won = ConnectK.parse(3, false,
            "XXX",
            "OO.",
            "...");
        AlphaBeta.Result r = new AlphaBeta(16, true).search(won, 3);
        assertEquals(-(WIN - 5), r.score());
        assertEquals(AlphaBeta.NO_MOVE, r.move());
        Position drawn = ConnectK.parse(3, false,
            "XOX",
            "XOO",
            "OXX");
        assertEquals(0, new AlphaBeta(0, false).search(drawn, 1).score());
    }

    @Test
    void badArgumentsAreRejected() {
        AlphaBeta ab = new AlphaBeta(0, false);
        assertThrows(IllegalArgumentException.class, () -> ab.search(ConnectK.ticTacToe(), 0));
        assertThrows(NullPointerException.class, () -> ab.search(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new AlphaBeta(-1, false));
        assertThrows(IllegalArgumentException.class, () -> ab.iterate(ConnectK.ticTacToe(), 0));
    }

    @Test
    void subtractionGamesFollowTheModuloRule() {
        for (int max = 1; max <= 5; max++) {
            for (int n = 1; n <= 60; n++) {
                for (boolean misere : new boolean[] {false, true}) {
                    SubtractionNim p = new SubtractionNim(n, max, misere);
                    AlphaBeta.Result r = new AlphaBeta(1 << 12, true).search(p, n);
                    int loserResidue = misere ? 1 : 0;
                    boolean moverLoses = n % (max + 1) == loserResidue;
                    String msg = "n=" + n + " max=" + max + " misere=" + misere;
                    assertEquals(moverLoses, r.score() < 0, msg);
                    if (!moverLoses) {
                        assertEquals(loserResidue, (n - r.move()) % (max + 1),
                            "winning move leaves a losing pile: " + msg);
                    }
                }
            }
        }
    }

    @Test
    void subtractionGameAgreesWithMinimaxForSmallPiles() {
        for (int max = 1; max <= 4; max++) {
            for (int n = 1; n <= 13; n++) {
                SubtractionNim p = new SubtractionNim(n, max, n % 2 == 0);
                int expected = Minimax.value(p, n);
                String msg = "n=" + n + " max=" + max;
                assertEquals(expected, new AlphaBeta(0, true).search(p, n).score(), msg);
                assertEquals(expected, new AlphaBeta(8, false).search(p, n).score(), msg);
            }
        }
    }
}
