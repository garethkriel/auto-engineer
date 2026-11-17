package ae.tsblock;

import java.io.IOException;

/** Thrown when a file is truncated, has bad magic numbers, or fails a checksum or structure check. */
public class CorruptFileException extends IOException {
    private static final long serialVersionUID = 1L;

    public CorruptFileException(String message) {
        super(message);
    }
}
