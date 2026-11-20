package ae.chess;

import java.util.List;

/** Standard Algebraic Notation for legal moves. */
public final class San {
    private San() { }

    /** Canonical SAN of a legal move. The position is restored before returning. */
    public static String toSan(Position p, Move m) {
        char piece = p.pieceAt(m.from());
        char type = Character.toUpperCase(piece);
        StringBuilder sb = new StringBuilder();
        boolean fileChange = m.from() % 8 != m.to() % 8;
        boolean capture = p.pieceAt(m.to()) != ' ' || (type == 'P' && fileChange);
        if (type == 'K' && Math.abs(m.to() - m.from()) == 2) {
            sb.append(m.to() > m.from() ? "O-O" : "O-O-O");
        } else {
            if (type == 'P') {
                if (capture) {
                    sb.append((char) ('a' + m.from() % 8));
                }
            } else {
                sb.append(type).append(disambiguation(p, m, piece));
            }
            if (capture) {
                sb.append('x');
            }
            sb.append(Move.squareName(m.to()));
            if (m.promo() != 0) {
                sb.append('=').append(Character.toUpperCase(m.promo()));
            }
        }
        Undo u = p.make(m);
        if (p.inCheck()) {
            sb.append(p.legalMoves().isEmpty() ? '#' : '+');
        }
        p.unmake(m, u);
        return sb.toString();
    }

    private static String disambiguation(Position p, Move m, char piece) {
        boolean any = false;
        boolean sameFile = false;
        boolean sameRank = false;
        for (Move o : p.legalMoves()) {
            if (o.to() == m.to() && o.from() != m.from() && p.pieceAt(o.from()) == piece) {
                any = true;
                sameFile |= o.from() % 8 == m.from() % 8;
                sameRank |= o.from() / 8 == m.from() / 8;
            }
        }
        if (!any) {
            return "";
        }
        String square = Move.squareName(m.from());
        if (!sameFile) {
            return square.substring(0, 1);
        }
        if (!sameRank) {
            return square.substring(1);
        }
        return square;
    }

    /** Finds the legal move whose canonical SAN equals {@code text} (ignoring {@code + # ! ?}). */
    public static Move parse(Position p, String text) {
        String wanted = strip(text);
        if (!wanted.isEmpty()) {
            List<Move> legal = p.legalMoves();
            for (Move m : legal) {
                if (strip(toSan(p, m)).equals(wanted)) {
                    return m;
                }
            }
        }
        throw new IllegalArgumentException("no legal move matches SAN '" + text + "' in " + p.toFen());
    }

    private static String strip(String s) {
        return s.replaceAll("[+#!?]+$", "");
    }
}
