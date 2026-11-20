package ae.erasure;

import java.io.IOException;

/** Shard files exist for a blob but fewer than k of them are valid. */
public class UnrecoverableBlobException extends IOException {
    private static final long serialVersionUID = 1L;

    public UnrecoverableBlobException(String message) {
        super(message);
    }
}
