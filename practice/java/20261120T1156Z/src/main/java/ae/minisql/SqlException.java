package ae.minisql;

/** Any parse, analysis, constraint or evaluation error raised by the engine. */
public final class SqlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SqlException(String message) {
        super(message);
    }
}
