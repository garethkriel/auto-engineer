package ae.tconf;

/** Thrown for any syntax or semantic error; carries the 1-based position of the offending token. */
public final class TomlException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int line;
    private final int column;

    public TomlException(int line, int column, String message) {
        super("line " + line + ", column " + column + ": " + message);
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
