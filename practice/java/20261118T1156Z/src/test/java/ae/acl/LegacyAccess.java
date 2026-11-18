package ae.acl;

import java.util.Locale;

/** Original messy implementation, kept as the oracle for characterisation and differential tests. */
final class LegacyAccess {
    private LegacyAccess() { }

    static String check(String role, String action, String type, String owner, String user,
                        boolean archived, int sens) {
        if (role == null) return "DENY:unknown-role";
        if (action == null) return "DENY:bad-action";
        String r = role.trim().toLowerCase(Locale.ROOT);
        String a = action.trim().toLowerCase(Locale.ROOT);
        if (!(r.equals("admin") || r.equals("editor") || r.equals("viewer") || r.equals("guest"))) {
            return "DENY:unknown-role";
        }
        if (!(a.equals("read") || a.equals("write") || a.equals("delete") || a.equals("share"))) {
            return "DENY:bad-action";
        }
        if (sens < 0 || sens > 3) return "DENY:bad-sensitivity";
        if (archived && (a.equals("write") || a.equals("delete"))) return "DENY:archived";
        boolean own = owner != null && owner.equals(user);
        String t = type == null ? "" : type;
        if (r.equals("admin")) {
            return "ALLOW";
        } else if (r.equals("editor")) {
            if (a.equals("read")) {
                if (sens <= 2 || own) return "ALLOW"; else return "DENY:sensitivity";
            } else if (a.equals("write")) {
                if (own) {
                    return "ALLOW";
                } else if (t.equals("doc") || t.equals("note")) {
                    if (sens <= 1) return "ALLOW"; else return "DENY:sensitivity";
                } else {
                    return "DENY:not-owner";
                }
            } else if (a.equals("delete")) {
                if (own) return "ALLOW"; else return "DENY:not-owner";
            } else {
                if (!own) return "DENY:not-owner";
                else if (sens > 2) return "DENY:sensitivity";
                else return "ALLOW";
            }
        } else if (r.equals("viewer")) {
            if (a.equals("read")) {
                if (sens <= 1 || (own && sens <= 2)) return "ALLOW"; else return "DENY:sensitivity";
            } else {
                return "DENY:role-limit";
            }
        } else {
            if (a.equals("read") && t.equals("public") && sens == 0) return "ALLOW";
            return "DENY:role-limit";
        }
    }
}
