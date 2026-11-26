package bench.minisql;

/** Thrown for every error reported by {@link Database}. */
public final class SqlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SqlException(String message) {
        super(message);
    }
}
