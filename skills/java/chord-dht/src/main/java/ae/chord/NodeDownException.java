package ae.chord;

/** Raised when an RPC targets a node that has crashed or left. */
public final class NodeDownException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NodeDownException(long id) {
        super("node " + id + " is down");
    }
}
