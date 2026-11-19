package bench.minisql;

/** Thrown for every lexical, syntax, type, name or runtime error. */
public final class SqlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SqlException(String message) {
        super(message);
    }
}
