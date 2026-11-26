package ae.tar;

import java.io.IOException;

/** Thrown when an archive is malformed, or when extracting it would be unsafe. */
public class TarFormatException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Creates the exception with a human-readable reason. */
    public TarFormatException(String message) {
        super(message);
    }
}
