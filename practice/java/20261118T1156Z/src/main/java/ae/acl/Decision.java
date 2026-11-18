package ae.acl;

import java.util.Objects;

/** Outcome of an access check. {@link #toString()} is the legacy wire format. */
public record Decision(boolean allowed, String reason) {
    public static final Decision ALLOW = new Decision(true, "");

    public Decision {
        Objects.requireNonNull(reason);
    }

    public static Decision deny(String reason) {
        return new Decision(false, reason);
    }

    @Override
    public String toString() {
        return allowed ? "ALLOW" : "DENY:" + reason;
    }
}
