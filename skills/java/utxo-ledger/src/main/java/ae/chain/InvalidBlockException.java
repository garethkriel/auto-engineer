package ae.chain;

/** Thrown when a block or transaction breaks a consensus rule. */
public class InvalidBlockException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidBlockException(String message) {
        super(message);
    }
}
