package ae.chord;

/** Raised when a lookup exceeds its hop budget (routing state is badly inconsistent). */
public final class LookupFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LookupFailedException(String message) {
        super(message);
    }
}
