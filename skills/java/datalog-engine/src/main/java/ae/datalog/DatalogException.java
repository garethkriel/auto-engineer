package ae.datalog;

/** Syntax or semantic error with the 1-based position of the offending token, atom or rule. */
public final class DatalogException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int line;
    private final int column;

    public DatalogException(int line, int column, String message) {
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
