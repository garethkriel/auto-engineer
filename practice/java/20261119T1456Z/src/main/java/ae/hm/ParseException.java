package ae.hm;

/** Syntax error with a 1-based line and column. */
public final class ParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int line;
    private final int col;

    public ParseException(int line, int col, String message) {
        super(message);
        this.line = line;
        this.col = col;
    }

    public int line() { return line; }

    public int col() { return col; }
}
