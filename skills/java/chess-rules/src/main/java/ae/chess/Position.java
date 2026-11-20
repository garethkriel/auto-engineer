package ae.chess;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A mutable chess position with make/unmake and legal move generation. Not thread safe.
 * The board is a 64-element char array: upper case = white, lower case = black, space = empty.
 */
public final class Position {

    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private static final int WK = 1;
    private static final int WQ = 2;
    private static final int BK = 4;
    private static final int BQ = 8;
    private static final int[][] KNIGHT = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
    private static final int[][] KING = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};
    private static final int[][] ORTHOGONAL = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private final char[] board = new char[64];
    private boolean white = true;
    private int castling;
    private int ep = -1;
    private int halfmove;
    private int fullmove = 1;

    private Position() {
        java.util.Arrays.fill(board, ' ');
    }

    // ---------------------------------------------------------------- FEN

    /** Parses a 4- or 6-field FEN, validating it strictly. */
    public static Position fromFen(String fen) {
        Objects.requireNonNull(fen, "fen");
        String[] f = fen.trim().split("\\s+");
        if (f.length != 4 && f.length != 6) {
            throw new IllegalArgumentException("FEN needs 4 or 6 fields: " + fen);
        }
        Position p = new Position();
        String[] rows = f[0].split("/", -1);
        if (rows.length != 8) {
            throw new IllegalArgumentException("FEN needs 8 ranks: " + fen);
        }
        for (int i = 0; i < 8; i++) {
            int rank = 7 - i;
            int file = 0;
            for (char ch : rows[i].toCharArray()) {
                if (ch >= '1' && ch <= '8') {
                    file += ch - '0';
                } else if ("PNBRQKpnbrqk".indexOf(ch) >= 0) {
                    if (file >= 8) {
                        throw new IllegalArgumentException("rank too long: " + rows[i]);
                    }
                    p.board[rank * 8 + file++] = ch;
                } else {
                    throw new IllegalArgumentException("bad character '" + ch + "' in FEN: " + fen);
                }
            }
            if (file != 8) {
                throw new IllegalArgumentException("rank does not have 8 files: " + rows[i]);
            }
        }
        int whiteKings = 0;
        int blackKings = 0;
        for (int sq = 0; sq < 64; sq++) {
            char c = p.board[sq];
            if (c == 'K') {
                whiteKings++;
            } else if (c == 'k') {
                blackKings++;
            } else if ((c == 'P' || c == 'p') && (sq < 8 || sq >= 56)) {
                throw new IllegalArgumentException("pawn on the first or last rank: " + fen);
            }
        }
        if (whiteKings != 1 || blackKings != 1) {
            throw new IllegalArgumentException("each side needs exactly one king: " + fen);
        }
        if (f[1].equals("w")) {
            p.white = true;
        } else if (f[1].equals("b")) {
            p.white = false;
        } else {
            throw new IllegalArgumentException("bad side to move: " + f[1]);
        }
        p.castling = parseCastling(p, f[2]);
        if (f[3].equals("-")) {
            p.ep = -1;
        } else {
            if (f[3].length() != 2 || f[3].charAt(0) < 'a' || f[3].charAt(0) > 'h'
                    || f[3].charAt(1) != (p.white ? '6' : '3')) {
                throw new IllegalArgumentException("bad en passant square: " + f[3]);
            }
            p.ep = Move.square(f[3]);
        }
        if (f.length == 6) {
            p.halfmove = parseInt(f[4], "halfmove clock", 0);
            p.fullmove = parseInt(f[5], "move number", 1);
        }
        if (p.isAttacked(p.kingSquare(!p.white), p.white)) {
            throw new IllegalArgumentException("side not to move is in check: " + fen);
        }
        return p;
    }

    private static int parseInt(String s, String what, int min) {
        try {
            int v = Integer.parseInt(s);
            if (v >= min) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the common error
        }
        throw new IllegalArgumentException("bad " + what + ": " + s);
    }

    private static int parseCastling(Position p, String s) {
        if (s.equals("-")) {
            return 0;
        }
        int rights = 0;
        for (char c : s.toCharArray()) {
            int bit = switch (c) {
                case 'K' -> WK;
                case 'Q' -> WQ;
                case 'k' -> BK;
                case 'q' -> BQ;
                default -> throw new IllegalArgumentException("bad castling field: " + s);
            };
            if ((rights & bit) != 0) {
                throw new IllegalArgumentException("duplicate castling right: " + s);
            }
            rights |= bit;
        }
        requireHome(p, rights, WK, 4, 'K', 7, 'R');
        requireHome(p, rights, WQ, 4, 'K', 0, 'R');
        requireHome(p, rights, BK, 60, 'k', 63, 'r');
        requireHome(p, rights, BQ, 60, 'k', 56, 'r');
        return rights;
    }

    private static void requireHome(Position p, int rights, int bit, int kingSq, char king, int rookSq, char rook) {
        if ((rights & bit) != 0 && (p.board[kingSq] != king || p.board[rookSq] != rook)) {
            throw new IllegalArgumentException("castling right without king and rook at home");
        }
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                char c = board[rank * 8 + file];
                if (c == ' ') {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(c);
                }
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (rank > 0) {
                sb.append('/');
            }
        }
        sb.append(white ? " w " : " b ");
        if (castling == 0) {
            sb.append('-');
        } else {
            if ((castling & WK) != 0) {
                sb.append('K');
            }
            if ((castling & WQ) != 0) {
                sb.append('Q');
            }
            if ((castling & BK) != 0) {
                sb.append('k');
            }
            if ((castling & BQ) != 0) {
                sb.append('q');
            }
        }
        sb.append(' ').append(ep < 0 ? "-" : Move.squareName(ep));
        sb.append(' ').append(halfmove).append(' ').append(fullmove);
        return sb.toString();
    }

    public Position copy() {
        Position p = new Position();
        System.arraycopy(board, 0, p.board, 0, 64);
        p.white = white;
        p.castling = castling;
        p.ep = ep;
        p.halfmove = halfmove;
        p.fullmove = fullmove;
        return p;
    }

    // ---------------------------------------------------------------- accessors

    public char pieceAt(int sq) {
        return board[sq];
    }

    public boolean whiteToMove() {
        return white;
    }

    public int halfmoveClock() {
        return halfmove;
    }

    public int fullmoveNumber() {
        return fullmove;
    }

    public int kingSquare(boolean forWhite) {
        char k = forWhite ? 'K' : 'k';
        for (int sq = 0; sq < 64; sq++) {
            if (board[sq] == k) {
                return sq;
            }
        }
        throw new IllegalStateException("no king on the board");
    }

    private static boolean isWhitePiece(char c) {
        return c >= 'A' && c <= 'Z';
    }

    private static boolean isBlackPiece(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static boolean on(int file, int rank) {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    // ---------------------------------------------------------------- attacks and status

    /** True if {@code sq} is attacked by any piece of the given colour (ignoring pins). */
    public boolean isAttacked(int sq, boolean byWhite) {
        int f = sq % 8;
        int r = sq / 8;
        int pawnRank = byWhite ? r - 1 : r + 1;
        char pawn = byWhite ? 'P' : 'p';
        for (int df = -1; df <= 1; df += 2) {
            if (on(f + df, pawnRank) && board[pawnRank * 8 + f + df] == pawn) {
                return true;
            }
        }
        char knight = byWhite ? 'N' : 'n';
        for (int[] d : KNIGHT) {
            if (on(f + d[0], r + d[1]) && board[(r + d[1]) * 8 + f + d[0]] == knight) {
                return true;
            }
        }
        char king = byWhite ? 'K' : 'k';
        for (int[] d : KING) {
            if (on(f + d[0], r + d[1]) && board[(r + d[1]) * 8 + f + d[0]] == king) {
                return true;
            }
        }
        char queen = byWhite ? 'Q' : 'q';
        char rook = byWhite ? 'R' : 'r';
        char bishop = byWhite ? 'B' : 'b';
        return rayHits(f, r, DIAGONAL, bishop, queen) || rayHits(f, r, ORTHOGONAL, rook, queen);
    }

    private boolean rayHits(int f, int r, int[][] dirs, char a, char b) {
        for (int[] d : dirs) {
            int x = f + d[0];
            int y = r + d[1];
            while (on(x, y)) {
                char c = board[y * 8 + x];
                if (c != ' ') {
                    if (c == a || c == b) {
                        return true;
                    }
                    break;
                }
                x += d[0];
                y += d[1];
            }
        }
        return false;
    }

    public boolean inCheck() {
        return isAttacked(kingSquare(white), !white);
    }

    public boolean isCheckmate() {
        return inCheck() && legalMoves().isEmpty();
    }

    public boolean isStalemate() {
        return !inCheck() && legalMoves().isEmpty();
    }

    /** Dead position by material: K v K, K+minor v K, or only bishops all on one square colour. */
    public boolean insufficientMaterial() {
        int minors = 0;
        int bishopsOnDark = 0;
        int bishopsOnLight = 0;
        int bishops = 0;
        for (int sq = 0; sq < 64; sq++) {
            char c = Character.toUpperCase(board[sq]);
            if (c == ' ' || c == 'K') {
                continue;
            }
            if (c == 'N') {
                minors++;
            } else if (c == 'B') {
                minors++;
                bishops++;
                if ((sq / 8 + sq % 8) % 2 == 0) {
                    bishopsOnDark++;
                } else {
                    bishopsOnLight++;
                }
            } else {
                return false; // pawn, rook or queen
            }
        }
        if (minors <= 1) {
            return true;
        }
        return bishops == minors && (bishopsOnDark == 0 || bishopsOnLight == 0);
    }

    /**
     * Key for repetition detection: placement, side, castling and the en passant square only when a legal
     * en passant capture exists.
     */
    public String repetitionKey() {
        String[] f = toFen().split(" ");
        String epField = "-";
        if (ep >= 0) {
            for (Move m : legalMoves()) {
                if (m.to() == ep && Character.toUpperCase(board[m.from()]) == 'P') {
                    epField = f[3];
                    break;
                }
            }
        }
        return f[0] + " " + f[1] + " " + f[2] + " " + epField;
    }

    // ---------------------------------------------------------------- make / unmake

    /** Plays a move that must be (pseudo-)legal; returns the data needed to undo it. */
    public Undo make(Move m) {
        int from = m.from();
        int to = m.to();
        char piece = board[from];
        char captured = board[to];
        int capSq = captured == ' ' ? -1 : to;
        boolean pawn = Character.toUpperCase(piece) == 'P';
        Undo undo;
        if (pawn && to == ep && captured == ' ' && from % 8 != to % 8) {
            capSq = to + (white ? -8 : 8);
            captured = board[capSq];
            board[capSq] = ' ';
        }
        undo = new Undo(piece, captured, capSq, castling, ep, halfmove, fullmove);
        board[to] = m.promo() == 0 ? piece : (white ? Character.toUpperCase(m.promo()) : m.promo());
        board[from] = ' ';
        if (Character.toUpperCase(piece) == 'K' && Math.abs(to - from) == 2) {
            int rookFrom = to > from ? from + 3 : from - 4;
            int rookTo = to > from ? from + 1 : from - 1;
            board[rookTo] = board[rookFrom];
            board[rookFrom] = ' ';
        }
        if (piece == 'K') {
            castling &= ~(WK | WQ);
        } else if (piece == 'k') {
            castling &= ~(BK | BQ);
        }
        castling &= ~rightFor(from) & ~rightFor(to);
        ep = pawn && Math.abs(to - from) == 16 ? (from + to) / 2 : -1;
        halfmove = pawn || captured != ' ' ? 0 : halfmove + 1;
        if (!white) {
            fullmove++;
        }
        white = !white;
        return undo;
    }

    private static int rightFor(int sq) {
        return switch (sq) {
            case 0 -> WQ;
            case 7 -> WK;
            case 56 -> BQ;
            case 63 -> BK;
            default -> 0;
        };
    }

    public void unmake(Move m, Undo u) {
        white = !white;
        int from = m.from();
        int to = m.to();
        board[from] = u.moved();
        board[to] = ' ';
        if (u.captureSquare() >= 0) {
            board[u.captureSquare()] = u.captured();
        }
        if (Character.toUpperCase(u.moved()) == 'K' && Math.abs(to - from) == 2) {
            int rookFrom = to > from ? from + 3 : from - 4;
            int rookTo = to > from ? from + 1 : from - 1;
            board[rookFrom] = board[rookTo];
            board[rookTo] = ' ';
        }
        castling = u.castling();
        ep = u.ep();
        halfmove = u.halfmove();
        fullmove = u.fullmove();
    }

    // ---------------------------------------------------------------- move generation

    /** All legal moves, in a deterministic order (by from-square, then generation order). */
    public List<Move> legalMoves() {
        List<Move> out = new ArrayList<>(48);
        boolean mover = white;
        for (Move m : pseudoLegal()) {
            Undo u = make(m);
            boolean safe = !isAttacked(kingSquare(mover), !mover);
            unmake(m, u);
            if (safe) {
                out.add(m);
            }
        }
        return out;
    }

    private List<Move> pseudoLegal() {
        List<Move> out = new ArrayList<>(64);
        for (int sq = 0; sq < 64; sq++) {
            char p = board[sq];
            if (p == ' ' || isWhitePiece(p) != white) {
                continue;
            }
            int f = sq % 8;
            int r = sq / 8;
            switch (Character.toUpperCase(p)) {
                case 'P' -> genPawn(out, sq, f, r);
                case 'N' -> genSteps(out, sq, f, r, KNIGHT);
                case 'K' -> {
                    genSteps(out, sq, f, r, KING);
                    genCastling(out, sq);
                }
                case 'B' -> genSlides(out, sq, f, r, DIAGONAL);
                case 'R' -> genSlides(out, sq, f, r, ORTHOGONAL);
                default -> {
                    genSlides(out, sq, f, r, DIAGONAL);
                    genSlides(out, sq, f, r, ORTHOGONAL);
                }
            }
        }
        return out;
    }

    private boolean isEnemy(char c) {
        return c != ' ' && isWhitePiece(c) != white;
    }

    private void genPawn(List<Move> out, int sq, int f, int r) {
        int dir = white ? 1 : -1;
        int startRank = white ? 1 : 6;
        int lastRank = white ? 7 : 0;
        int nr = r + dir;
        if (board[nr * 8 + f] == ' ') {
            addPawnMove(out, sq, nr * 8 + f, nr == lastRank);
            if (r == startRank && board[(r + 2 * dir) * 8 + f] == ' ') {
                out.add(new Move(sq, (r + 2 * dir) * 8 + f, (char) 0));
            }
        }
        for (int df = -1; df <= 1; df += 2) {
            if (!on(f + df, nr)) {
                continue;
            }
            int t = nr * 8 + f + df;
            if (isEnemy(board[t])) {
                addPawnMove(out, sq, t, nr == lastRank);
            } else if (t == ep && board[t] == ' ' && board[r * 8 + f + df] == (white ? 'p' : 'P')) {
                out.add(new Move(sq, t, (char) 0));
            }
        }
    }

    private static void addPawnMove(List<Move> out, int from, int to, boolean promotes) {
        if (promotes) {
            for (char c : new char[] {'q', 'r', 'b', 'n'}) {
                out.add(new Move(from, to, c));
            }
        } else {
            out.add(new Move(from, to, (char) 0));
        }
    }

    private void genSteps(List<Move> out, int sq, int f, int r, int[][] offsets) {
        for (int[] d : offsets) {
            int x = f + d[0];
            int y = r + d[1];
            if (on(x, y)) {
                char c = board[y * 8 + x];
                if (c == ' ' || isEnemy(c)) {
                    out.add(new Move(sq, y * 8 + x, (char) 0));
                }
            }
        }
    }

    private void genSlides(List<Move> out, int sq, int f, int r, int[][] dirs) {
        for (int[] d : dirs) {
            int x = f + d[0];
            int y = r + d[1];
            while (on(x, y)) {
                char c = board[y * 8 + x];
                if (c == ' ') {
                    out.add(new Move(sq, y * 8 + x, (char) 0));
                } else {
                    if (isEnemy(c)) {
                        out.add(new Move(sq, y * 8 + x, (char) 0));
                    }
                    break;
                }
                x += d[0];
                y += d[1];
            }
        }
    }

    private void genCastling(List<Move> out, int sq) {
        int home = white ? 4 : 60;
        if (sq != home) {
            return;
        }
        int kingRight = white ? WK : BK;
        int queenRight = white ? WQ : BQ;
        boolean enemy = !white;
        if ((castling & (kingRight | queenRight)) == 0 || isAttacked(home, enemy)) {
            return;
        }
        if ((castling & kingRight) != 0 && board[home + 1] == ' ' && board[home + 2] == ' '
                && board[home + 3] == (white ? 'R' : 'r')
                && !isAttacked(home + 1, enemy) && !isAttacked(home + 2, enemy)) {
            out.add(new Move(home, home + 2, (char) 0));
        }
        if ((castling & queenRight) != 0 && board[home - 1] == ' ' && board[home - 2] == ' '
                && board[home - 3] == ' ' && board[home - 4] == (white ? 'R' : 'r')
                && !isAttacked(home - 1, enemy) && !isAttacked(home - 2, enemy)) {
            out.add(new Move(home, home - 2, (char) 0));
        }
    }
}
