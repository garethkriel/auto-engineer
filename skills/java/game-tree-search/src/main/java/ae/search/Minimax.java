package ae.search;

/** Plain depth-limited negamax without pruning: the oracle the alpha-beta searcher is checked against. */
public final class Minimax {
    private long nodes;

    /** Score of a finished game from the mover's view, or 0 for a draw. */
    static int terminalScore(Position p) {
        return switch (p.status()) {
            case MOVER_LOST -> -(AlphaBeta.WIN - p.ply());
            case MOVER_WON -> AlphaBeta.WIN - p.ply();
            case DRAW -> 0;
            case ONGOING -> throw new IllegalArgumentException("position is not finished");
        };
    }

    /** Negamax value of {@code p} searching {@code depth} plies; leaves use the heuristic. */
    public int search(Position p, int depth) {
        nodes++;
        if (p.status() != Position.Status.ONGOING) {
            return terminalScore(p);
        }
        if (depth <= 0) {
            return p.heuristic();
        }
        int best = Integer.MIN_VALUE;
        for (int m : p.moves()) {
            best = Math.max(best, -search(p.play(m), depth - 1));
        }
        return best;
    }

    /** Number of positions visited by this instance so far. */
    public long nodes() {
        return nodes;
    }

    public static int value(Position p, int depth) {
        return new Minimax().search(p, depth);
    }
}
