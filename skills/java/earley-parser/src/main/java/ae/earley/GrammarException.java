package ae.earley;

/** Thrown for a malformed grammar text; the message starts with {@code line N:}. */
public final class GrammarException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GrammarException(int line, String message) {
        super("line " + line + ": " + message);
    }
}
