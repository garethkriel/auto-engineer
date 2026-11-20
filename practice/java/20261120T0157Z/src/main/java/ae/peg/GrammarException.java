package ae.peg;

/** Thrown for grammar syntax errors and failed static analysis. */
public final class GrammarException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GrammarException(String message) {
        super(message);
    }
}
