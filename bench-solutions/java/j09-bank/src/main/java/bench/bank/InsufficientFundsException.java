package bench.bank;

/** Thrown when an account holds less than the amount to transfer. */
public class InsufficientFundsException extends Exception {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
