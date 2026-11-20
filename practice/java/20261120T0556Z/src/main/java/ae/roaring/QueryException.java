package ae.roaring;

/** A query syntax or semantic error; {@link #column()} is the 1-based column of the offending token. */
public final class QueryException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int column;

    public QueryException(String message, int column) {
        super(message + " at column " + column);
        this.column = column;
    }

    public int column() {
        return column;
    }
}
