package ae.saga.port;

/** Failure of a saga step. Retryable failures are attempted again by the coordinator. */
public class StepException extends Exception {
    private static final long serialVersionUID = 1L;
    private final boolean retryable;

    public StepException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
