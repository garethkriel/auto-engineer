package ae.acl;

import java.util.Objects;
import java.util.Optional;

/** Entry points. Validation order (role, action, sensitivity, archived) is legacy behaviour. */
public final class AccessChecker {
    private AccessChecker() { }

    /** Typed API for already-parsed inputs. */
    public static Decision decide(Role role, Action action, Resource res, String user) {
        Objects.requireNonNull(role);
        Objects.requireNonNull(action);
        Objects.requireNonNull(res);
        if (!res.validSensitivity()) {
            return Decision.deny("bad-sensitivity");
        }
        if (res.archived() && action.mutates()) {
            return Decision.deny("archived");
        }
        return role.evaluate(action, res, user);
    }

    /** String API, byte-for-byte compatible with the legacy checker. */
    public static String check(String role, String action, String type, String owner, String user,
                               boolean archived, int sensitivity) {
        // Legacy quirk: null role, then null action, are rejected before the role name is validated.
        if (role == null) {
            return "DENY:unknown-role";
        }
        if (action == null) {
            return "DENY:bad-action";
        }
        Optional<Role> r = Role.parse(role);
        if (r.isEmpty()) {
            return "DENY:unknown-role";
        }
        Optional<Action> a = Action.parse(action);
        if (a.isEmpty()) {
            return "DENY:bad-action";
        }
        return decide(r.get(), a.get(), new Resource(type, owner, archived, sensitivity), user).toString();
    }
}
