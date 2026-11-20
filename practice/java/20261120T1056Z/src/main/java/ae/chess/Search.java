package ae.chess;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Small material-only search plus a forced-mate finder. */
public final class Search {

    /** Score of a checkmate delivered at ply 0; a mate at ply n scores {@code MATE - n}. */
    public static final int MATE = 100_000;

    private Search() { }

    /** Best root move ({@code null} when the game is over) and its score for the side to move. */
    public record Best(Move move, int score) { }

    /** A forced mate: first move and the number of moves by the mating side (1 = mate in one). */
    public record Mate(Move move, int moves) { }

    static int value(char piece) {
        return switch (Character.toUpperCase(piece)) {
            case 'P' -> 100;
            case 'N', 'B' -> 300;
            case 'R' -> 500;
            case 'Q' -> 900;
            default -> 0;
        };
    }

    /** Material balance from the side to move's point of view. */
    static int eval(Position p) {
        int sum = 0;
        for (int sq = 0; sq < 64; sq++) {
            char c = p.pieceAt(sq);
            if (c != ' ') {
                sum += Character.isUpperCase(c) ? value(c) : -value(c);
            }
        }
        return p.whiteToMove() ? sum : -sum;
    }

    /** Plain fixed-depth negamax, used as the oracle for {@link #alphaBeta}. */
    public static int negamax(Position p, int depth) {
        return negamax(p, depth, 0);
    }

    private static int negamax(Position p, int depth, int ply) {
        List<Move> moves = p.legalMoves();
        if (moves.isEmpty()) {
            return p.inCheck() ? -(MATE - ply) : 0;
        }
        if (p.insufficientMaterial()) {
            return 0;
        }
        if (depth == 0) {
            return eval(p);
        }
        int best = Integer.MIN_VALUE;
        for (Move m : moves) {
            Undo u = p.make(m);
            best = Math.max(best, -negamax(p, depth - 1, ply + 1));
            p.unmake(m, u);
        }
        return best;
    }

    /** Alpha-beta with captures-first ordering. Scores equal {@link #negamax} at the same depth. */
    public static Best alphaBeta(Position p, int depth) {
        List<Move> moves = ordered(p, p.legalMoves());
        if (moves.isEmpty()) {
            return new Best(null, p.inCheck() ? -MATE : 0);
        }
        if (p.insufficientMaterial()) {
            return new Best(moves.get(0), 0);
        }
        Move bestMove = null;
        int alpha = -MATE - 1;
        int beta = MATE + 1;
        for (Move m : moves) {
            Undo u = p.make(m);
            int score = -search(p, depth - 1, 1, -beta, -alpha);
            p.unmake(m, u);
            if (bestMove == null || score > alpha) {
                bestMove = m;
                alpha = Math.max(alpha, score);
            }
        }
        return new Best(bestMove, alpha);
    }

    private static int search(Position p, int depth, int ply, int alpha, int beta) {
        List<Move> moves = p.legalMoves();
        if (moves.isEmpty()) {
            return p.inCheck() ? -(MATE - ply) : 0;
        }
        if (p.insufficientMaterial()) {
            return 0;
        }
        if (depth == 0) {
            return eval(p);
        }
        for (Move m : ordered(p, moves)) {
            Undo u = p.make(m);
            int score = -search(p, depth - 1, ply + 1, -beta, -alpha);
            p.unmake(m, u);
            if (score >= beta) {
                return score;
            }
            alpha = Math.max(alpha, score);
        }
        return alpha;
    }

    private static List<Move> ordered(Position p, List<Move> moves) {
        List<Move> out = new ArrayList<>(moves);
        out.sort((a, b) -> Integer.compare(priority(p, b), priority(p, a)));
        return out;
    }

    private static int priority(Position p, Move m) {
        return value(p.pieceAt(m.to())) * 10 - value(p.pieceAt(m.from())) + (m.promo() == 0 ? 0 : 800);
    }

    /** Shortest forced mate for the side to move within {@code maxMoves} of its own moves. */
    public static Optional<Mate> shortestMate(Position p, int maxMoves) {
        for (int n = 1; n <= maxMoves; n++) {
            for (Move m : p.legalMoves()) {
                Undo u = p.make(m);
                boolean wins = defenceFails(p, n - 1);
                p.unmake(m, u);
                if (wins) {
                    return Optional.of(new Mate(m, n));
                }
            }
        }
        return Optional.empty();
    }

    /** After the attacker's move: true if the defender is mated now or every reply allows mate in n more. */
    private static boolean defenceFails(Position p, int n) {
        List<Move> replies = p.legalMoves();
        if (replies.isEmpty()) {
            return p.inCheck();
        }
        if (n == 0) {
            return false;
        }
        for (Move reply : replies) {
            Undo u = p.make(reply);
            boolean mated = attackerMates(p, n);
            p.unmake(reply, u);
            if (!mated) {
                return false;
            }
        }
        return true;
    }

    private static boolean attackerMates(Position p, int n) {
        for (Move m : p.legalMoves()) {
            Undo u = p.make(m);
            boolean wins = defenceFails(p, n - 1);
            p.unmake(m, u);
            if (wins) {
                return true;
            }
        }
        return false;
    }
}
