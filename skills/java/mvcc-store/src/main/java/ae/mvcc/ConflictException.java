package ae.mvcc;

/** Thrown by {@link Transaction#commit()} when validation fails; the transaction is aborted. */
public class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
