package ae.crypto;

/** Unchecked failure of an authenticated or padded decryption. Messages are deliberately uniform. */
public final class CryptoException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CryptoException(String message) {
        super(message);
    }
}
