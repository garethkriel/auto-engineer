package ae.layout;

/** Thrown when no layout satisfies the tolerance and width constraints. */
public class LayoutException extends Exception {
    private static final long serialVersionUID = 1L;

    public LayoutException(String message) {
        super(message);
    }
}
