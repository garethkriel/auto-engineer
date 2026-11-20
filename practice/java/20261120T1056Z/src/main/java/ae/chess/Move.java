package ae.chess;

/**
 * A chess move. Squares are {@code rank * 8 + file} with a1 = 0 and h8 = 63. {@code promo} is a lower-case
 * piece letter ({@code q r b n}) or 0. Castling is the king moving two files; en passant is a pawn moving
 * diagonally onto the (empty) en passant square.
 */
public record Move(int from, int to, char promo) {

    public Move {
        if (from < 0 || from > 63 || to < 0 || to > 63) {
            throw new IllegalArgumentException("square out of range: " + from + "," + to);
        }
        if (promo != 0 && "qrbn".indexOf(promo) < 0) {
            throw new IllegalArgumentException("bad promotion piece: " + promo);
        }
    }

    /** Parses UCI text such as {@code e2e4} or {@code e7e8q}. */
    public static Move of(String uci) {
        if (uci == null || (uci.length() != 4 && uci.length() != 5)) {
            throw new IllegalArgumentException("bad UCI move: " + uci);
        }
        char promo = uci.length() == 5 ? uci.charAt(4) : 0;
        return new Move(square(uci.substring(0, 2)), square(uci.substring(2, 4)), promo);
    }

    /** Square index of a name such as {@code "e4"}. */
    public static int square(String name) {
        if (name == null || name.length() != 2 || name.charAt(0) < 'a' || name.charAt(0) > 'h'
                || name.charAt(1) < '1' || name.charAt(1) > '8') {
            throw new IllegalArgumentException("bad square: " + name);
        }
        return (name.charAt(1) - '1') * 8 + (name.charAt(0) - 'a');
    }

    public static String squareName(int sq) {
        return "" + (char) ('a' + sq % 8) + (char) ('1' + sq / 8);
    }

    @Override
    public String toString() {
        return squareName(from) + squareName(to) + (promo == 0 ? "" : String.valueOf(promo));
    }
}
