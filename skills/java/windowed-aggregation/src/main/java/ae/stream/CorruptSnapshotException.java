package ae.stream;

/** Thrown by {@link WindowEngine#restore} when a snapshot fails its checksum or structural validation. */
public class CorruptSnapshotException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CorruptSnapshotException(String message) {
        super(message);
    }
}
