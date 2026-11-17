package ae.heap;

import java.io.IOException;

/** Thrown when a page or heap file fails its checksum or structural validation. */
public class CorruptDataException extends IOException {
    private static final long serialVersionUID = 1L;

    public CorruptDataException(String message) {
        super(message);
    }
}
