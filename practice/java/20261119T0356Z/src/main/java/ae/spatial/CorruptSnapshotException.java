package ae.spatial;

/** Thrown by {@link SpatialIndex#restore(byte[])} for any malformed snapshot. */
public class CorruptSnapshotException extends Exception {
    private static final long serialVersionUID = 1L;

    public CorruptSnapshotException(String message) {
        super(message);
    }
}
