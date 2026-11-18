package ae.acl;

import java.util.Locale;
import java.util.Optional;

/** What a caller wants to do with a resource. */
public enum Action {
    READ, WRITE, DELETE, SHARE;

    /** Parses a trimmed, case-insensitive name; empty if unknown or null. */
    public static Optional<Action> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        for (Action a : values()) {
            if (a.name().toLowerCase(Locale.ROOT).equals(t)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** True for actions that change the resource (blocked on archived resources). */
    public boolean mutates() {
        return this == WRITE || this == DELETE;
    }
}
