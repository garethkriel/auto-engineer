package ae.resp;

import java.io.IOException;

/** A RESP protocol violation. {@link #truncated()} is true when the input simply ended mid-value. */
public final class RespException extends IOException {
    private static final long serialVersionUID = 1L;
    private final boolean truncated;

    public RespException(String message, boolean truncated) {
        super(message);
        this.truncated = truncated;
    }

    public boolean truncated() {
        return truncated;
    }
}
