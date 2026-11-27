package bench.minisql;

/** Thrown for every error raised while executing a statement. */
public final class SqlException extends RuntimeException {
    public SqlException(String message) {
        super(message);
    }
}
