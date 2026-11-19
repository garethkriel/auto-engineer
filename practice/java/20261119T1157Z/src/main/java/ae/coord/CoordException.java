package ae.coord;

/** Failure of a coordination call. {@link #opIndex()} is the failing op of a multi, else -1. */
public final class CoordException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Machine-readable reason. */
    public enum Code { NO_NODE, NODE_EXISTS, NOT_EMPTY, BAD_VERSION, EPHEMERAL_PARENT, SESSION_EXPIRED, BAD_PATH }

    private final Code code;
    private final int opIndex;
    private final String path;

    public CoordException(Code code, String path, int opIndex) {
        super(code + (path == null ? "" : " at " + path) + (opIndex >= 0 ? " (op " + opIndex + ")" : ""));
        this.code = code;
        this.opIndex = opIndex;
        this.path = path;
    }

    public Code code() {
        return code;
    }

    public int opIndex() {
        return opIndex;
    }

    CoordException withOpIndex(int index) {
        return new CoordException(code, path, index);
    }
}
