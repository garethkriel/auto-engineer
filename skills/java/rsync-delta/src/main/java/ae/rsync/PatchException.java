package ae.rsync;

/** A delta cannot be applied to the given basis (wrong basis, out-of-range copy, digest mismatch). */
public final class PatchException extends Exception {
    private static final long serialVersionUID = 1L;

    public PatchException(String message) {
        super(message);
    }
}
