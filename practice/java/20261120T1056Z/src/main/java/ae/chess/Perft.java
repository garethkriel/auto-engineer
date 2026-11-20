package ae.chess;

import java.util.LinkedHashMap;
import java.util.Map;

/** Move-path enumeration used to validate the move generator against published node counts. */
public final class Perft {
    private Perft() { }

    /** Number of leaf nodes reachable in exactly {@code depth} plies. */
    public static long count(Position p, int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0");
        }
        if (depth == 0) {
            return 1;
        }
        var moves = p.legalMoves();
        if (depth == 1) {
            return moves.size();
        }
        long total = 0;
        for (Move m : moves) {
            Undo u = p.make(m);
            total += count(p, depth - 1);
            p.unmake(m, u);
        }
        return total;
    }

    /** Per-root-move node counts, in UCI text, in generation order. */
    public static Map<String, Long> divide(Position p, int depth) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Move m : p.legalMoves()) {
            Undo u = p.make(m);
            out.put(m.toString(), count(p, depth - 1));
            p.unmake(m, u);
        }
        return out;
    }
}
