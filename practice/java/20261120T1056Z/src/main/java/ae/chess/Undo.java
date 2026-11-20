package ae.chess;

/** Everything {@link Position#unmake} needs to restore the position before a move. */
public record Undo(char moved, char captured, int captureSquare, int castling, int ep, int halfmove,
                   int fullmove) {
}
