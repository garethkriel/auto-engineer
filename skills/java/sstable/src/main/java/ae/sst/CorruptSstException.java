package ae.sst;

import java.io.IOException;

/** Thrown when an SSTable file fails any structural or checksum validation. */
public final class CorruptSstException extends IOException {
    private static final long serialVersionUID = 1L;

    public CorruptSstException(String message) {
        super(message);
    }
}
