package ae.hm;

/** Type error with a 1-based line and column. */
public final class TypeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Kind { MISMATCH, OCCURS, UNBOUND }

    private final Kind kind;
    private final int line;
    private final int col;

    public TypeException(Kind kind, int line, int col, String message) {
        super(message);
        this.kind = kind;
        this.line = line;
        this.col = col;
    }

    public Kind kind() { return kind; }

    public int line() { return line; }

    public int col() { return col; }
}
