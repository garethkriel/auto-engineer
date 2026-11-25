package ae.search;

import java.util.Arrays;
import java.util.Objects;

/**
 * Fail-soft negamax alpha-beta search with an optional transposition table, move ordering and iterative deepening.
 *
 * <p>Scores are from the mover's view. A finished game scores {@code +-(WIN - ply)} or 0, so the value of a
 * position never depends on the path that reached it and table entries need no mate-distance correction.
 * Heuristic values stay below {@code WIN / 2}; a score beyond that is a proven win or loss.
 *
 * <p>One instance keeps its table between calls; use it for one kind of game only (keys of different boards may
 * collide) or call {@link #clear()}.
 */
public final class AlphaBeta {
    public static final int WIN = 10_000;
    public static final int NO_MOVE = -1;
    private static final int INF = Integer.MAX_VALUE / 2;

    /** Outcome of a root search. {@code depth} is the depth that was searched to. */
    public record Result(int score, int move, long nodes, int depth) { }

    private final TranspositionTable table;
    private final boolean ordering;
    private long nodes;

    /** @param ttEntries table size, 0 for no table */
    public AlphaBeta(int ttEntries, boolean ordering) {
        if (ttEntries < 0) {
            throw new IllegalArgumentException("negative table size");
        }
        this.table = ttEntries == 0 ? null : new TranspositionTable(ttEntries);
        this.ordering = ordering;
    }

    /** Nodes visited by the most recent {@code search}, {@code analyse} or {@code iterate} call. */
    public long nodes() {
        return nodes;
    }

    public void clear() {
        if (table != null) {
            table.clear();
        }
    }

    public Result search(Position root, int depth) {
        Objects.requireNonNull(root, "root");
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be at least 1: " + depth);
        }
        nodes = 0;
        return rootSearch(root, depth, NO_MOVE);
    }

    private Result rootSearch(Position root, int depth, int hint) {
        if (root.status() != Position.Status.ONGOING) {
            nodes++;
            return new Result(Minimax.terminalScore(root), NO_MOVE, nodes, depth);
        }
        nodes++;
        int[] moves = orderedMoves(root, hint);
        int alpha = -INF;
        int best = -INF;
        int bestMove = NO_MOVE;
        for (int m : moves) {
            int v = -negamax(root.play(m), depth - 1, -INF, -alpha);
            if (v > best) {
                best = v;
                bestMove = m;
            }
            alpha = Math.max(alpha, v);
        }
        if (table != null) {
            table.store(root.key(), depth, TranspositionTable.Bound.EXACT, best, bestMove);
        }
        return new Result(best, bestMove, nodes, depth);
    }

    /** Exact value of every root move, in the order of {@code root.moves()}. */
    public int[] analyse(Position root, int depth) {
        Objects.requireNonNull(root, "root");
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be at least 1: " + depth);
        }
        nodes = 0;
        int[] moves = root.moves();
        int[] scores = new int[moves.length];
        for (int i = 0; i < moves.length; i++) {
            scores[i] = -negamax(root.play(moves[i]), depth - 1, -INF, INF);
        }
        return scores;
    }

    /** Iterative deepening to {@code maxDepth}; stops early once the root value is a proven win or loss. */
    public Result iterate(Position root, int maxDepth) {
        Objects.requireNonNull(root, "root");
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1: " + maxDepth);
        }
        nodes = 0;
        Result r = null;
        int hint = NO_MOVE;
        for (int d = 1; d <= maxDepth; d++) {
            r = rootSearch(root, d, hint);
            hint = r.move();
            if (root.status() != Position.Status.ONGOING || Math.abs(r.score()) > WIN / 2) {
                break;
            }
        }
        return r;
    }

    private int negamax(Position p, int depth, int alpha, int beta) {
        nodes++;
        if (p.status() != Position.Status.ONGOING) {
            return Minimax.terminalScore(p);
        }
        if (depth <= 0) {
            return p.heuristic();
        }
        int originalAlpha = alpha;
        int hint = NO_MOVE;
        if (table != null) {
            TranspositionTable.Entry e = table.probe(p.key());
            if (e != null) {
                hint = e.move();
                if (e.depth() >= depth) {
                    switch (e.bound()) {
                        case EXACT -> {
                            return e.value();
                        }
                        case LOWER -> alpha = Math.max(alpha, e.value());
                        case UPPER -> beta = Math.min(beta, e.value());
                    }
                    if (alpha >= beta) {
                        return e.value();
                    }
                }
            }
        }
        int best = -INF;
        int bestMove = NO_MOVE;
        for (int m : orderedMoves(p, hint)) {
            int v = -negamax(p.play(m), depth - 1, -beta, -alpha);
            if (v > best) {
                best = v;
                bestMove = m;
            }
            alpha = Math.max(alpha, v);
            if (alpha >= beta) {
                break;
            }
        }
        if (table != null) {
            TranspositionTable.Bound bound = best <= originalAlpha ? TranspositionTable.Bound.UPPER
                : best >= beta ? TranspositionTable.Bound.LOWER : TranspositionTable.Bound.EXACT;
            table.store(p.key(), depth, bound, best, bestMove);
        }
        return best;
    }

    /** Moves with {@code hint} first, then (if ordering is on) by the child's heuristic, best for the mover first. */
    private int[] orderedMoves(Position p, int hint) {
        int[] moves = p.moves();
        if (!ordering) {
            return moveToFront(moves, hint);
        }
        int n = moves.length;
        long[] keyed = new long[n];
        for (int i = 0; i < n; i++) {
            Position child = p.play(moves[i]);
            int s = child.status() == Position.Status.ONGOING ? -child.heuristic() : -Minimax.terminalScore(child);
            // Sort ascending on (-score, index): best score first, ties keep the original order.
            keyed[i] = ((long) (-s) << 32) | i;
        }
        Arrays.sort(keyed);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = moves[(int) (keyed[i] & 0xFFFFFFFFL)];
        }
        return moveToFront(out, hint);
    }

    private static int[] moveToFront(int[] moves, int hint) {
        if (hint == NO_MOVE) {
            return moves;
        }
        for (int i = 0; i < moves.length; i++) {
            if (moves[i] == hint) {
                System.arraycopy(moves, 0, moves, 1, i);
                moves[0] = hint;
                break;
            }
        }
        return moves;
    }
}
