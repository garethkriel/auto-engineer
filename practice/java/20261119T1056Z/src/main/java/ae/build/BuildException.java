package ae.build;

import java.util.Objects;

/** A key failed to build. {@link #key()} names the key whose own task (or lookup) failed. */
public class BuildException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String key;

    public BuildException(String key, String message, Throwable cause) {
        super(message, cause);
        this.key = Objects.requireNonNull(key);
    }

    /** The key that failed first (not the dependents that merely propagated the failure). */
    public String key() {
        return key;
    }
}
