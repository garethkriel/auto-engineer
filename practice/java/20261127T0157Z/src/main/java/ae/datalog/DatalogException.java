package ae.datalog;

/** Thrown for syntax errors and for programs that fail validation (arity, safety, stratification). */
public final class DatalogException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DatalogException(String message) {
        super(message);
    }
}
