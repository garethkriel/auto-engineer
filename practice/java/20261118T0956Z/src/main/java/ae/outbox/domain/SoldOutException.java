package ae.outbox.domain;

/** Thrown when a show has fewer free seats than requested. */
public final class SoldOutException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public SoldOutException(String message) {
        super(message);
    }
}
