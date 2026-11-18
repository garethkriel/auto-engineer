package ae.deps;

/** Thrown when no consistent set of versions exists or the search budget is spent. */
public class ResolutionException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Creates the exception with an explanation naming the package and its requirers. */
    public ResolutionException(String message) {
        super(message);
    }
}
