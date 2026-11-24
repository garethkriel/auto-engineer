package bench.minisql;

/** Any SQL error: syntax, name resolution, type or runtime. */
public final class SqlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SqlException(String message) {
        super(message);
    }
}
