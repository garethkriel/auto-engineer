package conc;

/** Unchecked wrapper thrown by {@link Parallel#mapOrdered}; the cause is the first failure. */
public class ParallelException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ParallelException(String message, Throwable cause) {
        super(message, cause);
    }
}
