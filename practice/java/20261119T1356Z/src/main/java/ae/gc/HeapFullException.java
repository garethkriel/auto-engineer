package ae.gc;

/** Thrown when an allocation cannot be satisfied even after collection and compaction. */
public final class HeapFullException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public HeapFullException(String message) {
        super(message);
    }
}
