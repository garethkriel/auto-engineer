package ae.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Exhaustive grid: refactored checker must equal the legacy one on every input. */
class DifferentialTest {
    private static final List<String> ROLES =
        java.util.Arrays.asList("admin", "ADMIN", " Editor ", "editor", "viewer", "Guest", "guest", "root", "", null);
    private static final List<String> ACTIONS =
        java.util.Arrays.asList("read", "WRITE", " delete", "share", "print", "", null);
    private static final List<String> TYPES = java.util.Arrays.asList("doc", "note", "img", "public", "", null);
    private static final List<String> OWNERS = java.util.Arrays.asList("alice", "bob", null);
    private static final List<String> USERS = java.util.Arrays.asList("alice", "bob", null);

    @Test
    void refactoredMatchesLegacyOnWholeGrid() {
        int n = 0;
        int allowed = 0;
        for (String role : ROLES) {
            for (String action : ACTIONS) {
                for (String type : TYPES) {
                    for (String owner : OWNERS) {
                        for (String user : USERS) {
                            for (boolean arch : new boolean[] {false, true}) {
                                for (int s = -1; s <= 4; s++) {
                                    String expected = LegacyAccess.check(role, action, type, owner, user, arch, s);
                                    String actual = AccessChecker.check(role, action, type, owner, user, arch, s);
                                    assertEquals(expected, actual, String.join("|", String.valueOf(role),
                                        String.valueOf(action), String.valueOf(type), String.valueOf(owner),
                                        String.valueOf(user), String.valueOf(arch), String.valueOf(s)));
                                    n++;
                                    if (expected.equals("ALLOW")) {
                                        allowed++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(10 * 7 * 6 * 3 * 3 * 2 * 6, n);
        assertTrue(allowed > 1000 && allowed < n / 2, "grid should mix allow and deny, allowed=" + allowed);
    }

    @Test
    void typedApiMatchesStringApi() {
        Decision d = AccessChecker.decide(Role.EDITOR, Action.SHARE, new Resource("doc", "al", false, 3), "al");
        assertEquals(Decision.deny("sensitivity"), d);
        assertEquals("DENY:sensitivity", d.toString());
        assertEquals(Decision.ALLOW, AccessChecker.decide(Role.GUEST, Action.READ, new Resource("public", null, false, 0), null));
    }

    @Test
    void parseRejectsUnknown() {
        assertTrue(Role.parse("boss").isEmpty());
        assertTrue(Action.parse(null).isEmpty());
        assertEquals(Role.VIEWER, Role.parse(" VIEWER ").orElseThrow());
    }
}
