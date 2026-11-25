package ae.search;

/**
 * An immutable game position, seen from the side to move (the "mover").
 *
 * <p>Contract used by the searchers:
 * <ul>
 *   <li>{@link #moves()} is empty exactly when {@link #status()} is not {@link Status#ONGOING};</li>
 *   <li>{@link #key()} is a 64-bit hash: positions with equal keys must agree on {@link #ply()},
 *       {@link #status()}, {@link #moves()} and {@link #heuristic()};</li>
 *   <li>{@link #heuristic()} is only read for ongoing positions and satisfies
 *       {@code |h| < AlphaBeta.WIN / 2}.</li>
 * </ul>
 */
public interface Position {
    /** Outcome of a position from the mover's point of view. */
    enum Status {
        ONGOING,
        /** The mover has lost (for example the opponent just completed a line). */
        MOVER_LOST,
        /** The mover has won. */
        MOVER_WON,
        DRAW
    }

    /** Legal moves, in a fixed order; empty if the game is over. */
    int[] moves();

    /** The position after the mover plays {@code move}; throws {@link IllegalArgumentException} for illegal moves. */
    Position play(int move);

    Status status();

    /** Static evaluation of an ongoing position from the mover's point of view. */
    int heuristic();

    /** Hash of the position (see class comment). */
    long key();

    /** Number of moves played since the start of the game. */
    int ply();
}
