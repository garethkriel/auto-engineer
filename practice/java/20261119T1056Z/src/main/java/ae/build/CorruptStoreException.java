package ae.build;

import java.io.IOException;

/** The persisted store failed validation (bad magic, checksum, length or structure). */
public class CorruptStoreException extends IOException {
    private static final long serialVersionUID = 1L;

    public CorruptStoreException(String message) {
        super(message);
    }
}
