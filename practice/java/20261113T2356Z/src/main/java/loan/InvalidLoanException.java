package loan;

/** Checked exception signalling loan parameters that cannot be amortized. */
public class InvalidLoanException extends Exception {
    private static final long serialVersionUID = 1L;

    public InvalidLoanException(String message) {
        super(message);
    }
}
