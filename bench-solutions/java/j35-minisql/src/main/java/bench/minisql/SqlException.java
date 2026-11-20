package bench.minisql;

/** Thrown for every error raised while executing a statement. */
public final class SqlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description of the error
     */
    public SqlException(String message) {
        super(message);
    }
}
