package ae.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Literal answers recorded from the legacy code; they must hold for legacy and refactored code. */
class CharacterisationTest {
    // columns: role, action, type, owner, user, archived, sensitivity, expected
    private static final String TABLE_NOTE = "recorded from LegacyAccess";

    @ParameterizedTest(name = "{0} {1} {2} owner={3} user={4} arch={5} s={6} -> {7}")
    @CsvSource({
        "admin,read,doc,bob,alice,false,3,ALLOW",
        "ADMIN,share,doc,bob,alice,false,3,ALLOW",
        "admin,write,doc,bob,alice,true,0,DENY:archived",
        "admin,delete,doc,bob,alice,true,0,DENY:archived",
        "admin,read,doc,bob,alice,true,3,ALLOW",
        "admin,read,doc,bob,alice,false,4,DENY:bad-sensitivity",
        "editor,read,doc,bob,alice,false,2,ALLOW",
        "editor,read,doc,bob,alice,false,3,DENY:sensitivity",
        "editor,read,doc,alice,alice,false,3,ALLOW",
        "editor,write,img,alice,alice,false,3,ALLOW",
        "editor,write,doc,bob,alice,false,1,ALLOW",
        "editor,write,note,bob,alice,false,2,DENY:sensitivity",
        "editor,write,img,bob,alice,false,0,DENY:not-owner",
        "editor,delete,doc,alice,alice,false,0,ALLOW",
        "editor,delete,doc,bob,alice,false,0,DENY:not-owner",
        "editor,delete,doc,alice,alice,true,0,DENY:archived",
        "editor,share,doc,alice,alice,false,2,ALLOW",
        "editor,share,doc,alice,alice,false,3,DENY:sensitivity",
        "editor,share,doc,bob,alice,false,0,DENY:not-owner",
        "viewer,read,doc,bob,alice,false,1,ALLOW",
        "viewer,read,doc,bob,alice,false,2,DENY:sensitivity",
        "viewer,read,doc,alice,alice,false,2,ALLOW",
        "viewer,read,doc,alice,alice,false,3,DENY:sensitivity",
        "viewer,write,doc,alice,alice,false,0,DENY:role-limit",
        "viewer,share,doc,alice,alice,false,0,DENY:role-limit",
        "guest,read,public,bob,alice,false,0,ALLOW",
        "guest,read,public,bob,alice,false,1,DENY:role-limit",
        "guest,read,doc,bob,alice,false,0,DENY:role-limit",
        "guest,delete,public,alice,alice,false,0,DENY:role-limit",
        "'  Editor ',' READ ',doc,bob,alice,false,0,ALLOW",
        "root,read,doc,bob,alice,false,0,DENY:unknown-role",
        "admin,print,doc,bob,alice,false,0,DENY:bad-action",
        "root,print,doc,bob,alice,false,9,DENY:unknown-role",
        "admin,print,doc,bob,alice,false,9,DENY:bad-action",
        "admin,read,doc,bob,alice,true,-1,DENY:bad-sensitivity",
    })
    void table(String role, String action, String type, String owner, String user,
               boolean archived, int sens, String expected) {
        assertEquals(expected, LegacyAccess.check(role, action, type, owner, user, archived, sens),
            "legacy: " + TABLE_NOTE);
        assertEquals(expected, AccessChecker.check(role, action, type, owner, user, archived, sens),
            "refactored");
    }

    @org.junit.jupiter.api.Test
    void nullsAreHandled() {
        assertEquals("DENY:unknown-role", AccessChecker.check(null, "read", "doc", "a", "a", false, 0));
        assertEquals("DENY:bad-action", AccessChecker.check("admin", null, "doc", "a", "a", false, 0));
        assertEquals("DENY:bad-action", AccessChecker.check("root", null, "doc", "a", "a", false, 0));
        assertEquals("DENY:not-owner", AccessChecker.check("editor", "write", null, null, null, false, 0));
        assertEquals("DENY:not-owner", AccessChecker.check("editor", "delete", "doc", null, null, false, 0));
        assertEquals("DENY:role-limit", AccessChecker.check("guest", "read", null, null, null, false, 0));
    }
}
