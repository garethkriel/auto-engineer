package bench.minisql;

/** Raised for every SQL error. */
public final class SqlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SqlException(String message) {
        super(message);
    }
}
