package ae.mdlite;

/** Thrown for malformed or disallowed Markdown; carries a 1-based line and column. */
public final class MarkdownException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int line;
    private final int column;

    public MarkdownException(int line, int column, String message) {
        super(line + ":" + column + ": " + message);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
