package ae.minisql;

/** Any lexical, syntactic, semantic or constraint error raised by the engine. */
public final class SqlException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int position;

    /** @param position 0-based offset into the sql text, or -1 when no single position applies */
    public SqlException(String message, int position) {
        super(message);
        this.position = position;
    }

    public SqlException(String message) {
        this(message, -1);
    }

    /** 0-based offset of the offending text, -1 if none. */
    public int position() {
        return position;
    }
}
