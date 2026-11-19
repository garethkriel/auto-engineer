package ae.broker;

/** The caller used a group generation that is no longer current. */
public final class StaleGenerationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StaleGenerationException(String message) { super(message); }
}
