package ae.lp;

/** Syntax error in LP text; {@link #line()} is 1-based. */
public final class LpParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int line;

    public LpParseException(int line, String message) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
