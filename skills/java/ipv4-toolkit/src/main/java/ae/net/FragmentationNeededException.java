package ae.net;

/** The packet does not fit the MTU and has the Don't-Fragment flag set. */
public class FragmentationNeededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FragmentationNeededException(String message) {
        super(message);
    }
}
