package ae.es.port;

/** Thrown when an append used an expected version that is not the stream's current version. */
public final class ConcurrencyException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final long expected;
    private final long actual;

    public ConcurrencyException(String streamId, long expected, long actual) {
        super("stream " + streamId + ": expected version " + expected + " but was " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public long expectedVersion() {
        return expected;
    }

    public long actualVersion() {
        return actual;
    }
}
