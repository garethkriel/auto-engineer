package ae.linalg;

/** Thrown when an operation needs a non-singular matrix. */
public class SingularMatrixException extends ArithmeticException {
    private static final long serialVersionUID = 1L;

    public SingularMatrixException(String message) {
        super(message);
    }
}
