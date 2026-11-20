package ae.chess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A game in progress: move history, repetition tracking, result detection and movetext. */
public final class Game {

    public enum Status {
        ONGOING, CHECKMATE, STALEMATE, DRAW_INSUFFICIENT_MATERIAL, DRAW_FIFTY_MOVES, DRAW_REPETITION
    }

    private final Position position;
    private final boolean startWhite;
    private final int startFullmove;
    private final List<String> san = new ArrayList<>();
    private final Map<String, Integer> seen = new HashMap<>();

    public Game() {
        this(Position.START_FEN);
    }

    public Game(String fen) {
        this.position = Position.fromFen(fen);
        this.startWhite = position.whiteToMove();
        this.startFullmove = position.fullmoveNumber();
        seen.merge(position.repetitionKey(), 1, Integer::sum);
    }

    /** Builds a game from movetext such as {@code "1. e4 e5 {comment} 2.Nf3 1-0"}. */
    public static Game fromMoveText(String text) {
        Game g = new Game();
        String cleaned = text.replaceAll("\\{[^}]*}", " ");
        for (String token : cleaned.trim().split("\\s+")) {
            String t = token.replaceFirst("^\\d+\\.+", "");
            if (t.isEmpty() || t.equals("*") || t.equals("1-0") || t.equals("0-1") || t.equals("1/2-1/2")) {
                continue;
            }
            g.playSan(t);
        }
        return g;
    }

    /** A copy of the current position. */
    public Position position() {
        return position.copy();
    }

    public List<String> history() {
        return List.copyOf(san);
    }

    public void playSan(String text) {
        requireOngoing();
        play(San.parse(position, text));
    }

    public void playUci(String uci) {
        requireOngoing();
        Move m = Move.of(uci);
        if (!position.legalMoves().contains(m)) {
            throw new IllegalArgumentException("illegal move " + uci + " in " + position.toFen());
        }
        play(m);
    }

    private void requireOngoing() {
        if (status() != Status.ONGOING) {
            throw new IllegalStateException("game is over: " + status());
        }
    }

    private void play(Move m) {
        String notation = San.toSan(position, m);
        position.make(m);
        san.add(notation);
        seen.merge(position.repetitionKey(), 1, Integer::sum);
    }

    public Status status() {
        if (position.isCheckmate()) {
            return Status.CHECKMATE;
        }
        if (position.legalMoves().isEmpty()) {
            return Status.STALEMATE;
        }
        if (position.insufficientMaterial()) {
            return Status.DRAW_INSUFFICIENT_MATERIAL;
        }
        if (seen.getOrDefault(position.repetitionKey(), 0) >= 3) {
            return Status.DRAW_REPETITION;
        }
        if (position.halfmoveClock() >= 100) {
            return Status.DRAW_FIFTY_MOVES;
        }
        return Status.ONGOING;
    }

    /** Numbered movetext, e.g. {@code "1. e4 e5 2. Nf3"}; a black first move prints as {@code "7... Kd8"}. */
    public String moveText() {
        StringBuilder sb = new StringBuilder();
        boolean whiteTurn = startWhite;
        int number = startFullmove;
        for (int i = 0; i < san.size(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (whiteTurn) {
                sb.append(number).append(". ");
            } else if (i == 0) {
                sb.append(number).append("... ");
            }
            sb.append(san.get(i));
            if (!whiteTurn) {
                number++;
            }
            whiteTurn = !whiteTurn;
        }
        return sb.toString();
    }
}
