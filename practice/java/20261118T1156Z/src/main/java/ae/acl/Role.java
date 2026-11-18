package ae.acl;

import java.util.Locale;
import java.util.Optional;

/** Roles; each one carries its own policy (replaces the legacy string if-chains). */
public enum Role {
    ADMIN {
        @Override
        Decision evaluate(Action action, Resource res, String user) {
            return Decision.ALLOW;
        }
    },
    EDITOR {
        @Override
        Decision evaluate(Action action, Resource res, String user) {
            boolean own = res.ownedBy(user);
            int s = res.sensitivity();
            return switch (action) {
                case READ -> own || s <= 2 ? Decision.ALLOW : Decision.deny("sensitivity");
                case WRITE -> {
                    if (own) {
                        yield Decision.ALLOW;
                    }
                    if (res.type().equals("doc") || res.type().equals("note")) {
                        yield s <= 1 ? Decision.ALLOW : Decision.deny("sensitivity");
                    }
                    yield Decision.deny("not-owner");
                }
                case DELETE -> own ? Decision.ALLOW : Decision.deny("not-owner");
                case SHARE -> {
                    if (!own) {
                        yield Decision.deny("not-owner");
                    }
                    yield s > 2 ? Decision.deny("sensitivity") : Decision.ALLOW;
                }
            };
        }
    },
    VIEWER {
        @Override
        Decision evaluate(Action action, Resource res, String user) {
            if (action != Action.READ) {
                return Decision.deny("role-limit");
            }
            int s = res.sensitivity();
            boolean ok = s <= 1 || (res.ownedBy(user) && s <= 2);
            return ok ? Decision.ALLOW : Decision.deny("sensitivity");
        }
    },
    GUEST {
        @Override
        Decision evaluate(Action action, Resource res, String user) {
            boolean ok = action == Action.READ && res.type().equals("public") && res.sensitivity() == 0;
            return ok ? Decision.ALLOW : Decision.deny("role-limit");
        }
    };

    abstract Decision evaluate(Action action, Resource res, String user);

    /** Parses a trimmed, case-insensitive name; empty if unknown or null. */
    public static Optional<Role> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        for (Role r : values()) {
            if (r.name().toLowerCase(Locale.ROOT).equals(t)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
