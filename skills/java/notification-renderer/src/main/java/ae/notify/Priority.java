package ae.notify;

import java.util.Locale;
import java.util.Optional;

/** Message priority; parsing keeps the legacy "trim, lower-case, exact match" rule. */
public enum Priority {
    LOW, NORMAL, HIGH;

    /** Parses a user-supplied priority; empty if it is not one of low/normal/high. */
    public static Optional<Priority> parse(String raw) {
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "low": return Optional.of(LOW);
            case "normal": return Optional.of(NORMAL);
            case "high": return Optional.of(HIGH);
            default: return Optional.empty();
        }
    }
}
