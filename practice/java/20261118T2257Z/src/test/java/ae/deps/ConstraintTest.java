package ae.deps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConstraintTest {
    private static void check(String constraint, String[] yes, String[] no) {
        Constraint c = Constraint.parse(constraint);
        for (String v : yes) assertTrue(c.matches(Version.parse(v)), constraint + " should match " + v);
        for (String v : no) assertFalse(c.matches(Version.parse(v)), constraint + " should not match " + v);
    }

    private static String[] a(String... s) {
        return s;
    }

    @Test
    void exactAndEquals() {
        check("1.2.3", a("1.2.3"), a("1.2.4", "1.2.2", "1.2.3-rc.1"));
        check("=1.2.3", a("1.2.3"), a("1.2.4"));
    }

    @Test
    void wildcardsAndPartials() {
        check("*", a("0.0.0", "99.9.9"), a("1.0.0-alpha"));
        check("", a("0.0.0", "5.5.5"), a());
        check("x", a("3.1.4"), a());
        check("1", a("1.0.0", "1.99.99"), a("0.9.9", "2.0.0"));
        check("1.x", a("1.0.0", "1.5.0"), a("2.0.0"));
        check("1.2", a("1.2.0", "1.2.9"), a("1.1.9", "1.3.0"));
        check("1.2.x", a("1.2.7"), a("1.3.0"));
        check("1.2.*", a("1.2.7"), a("1.3.0"));
    }

    @Test
    void caret() {
        check("^1.2.3", a("1.2.3", "1.9.0", "1.99.99"), a("1.2.2", "2.0.0", "0.9.0"));
        check("^0.2.3", a("0.2.3", "0.2.9"), a("0.3.0", "0.2.2", "1.0.0"));
        check("^0.0.3", a("0.0.3"), a("0.0.4", "0.0.2"));
        check("^1.2", a("1.2.0", "1.8.0"), a("1.1.9", "2.0.0"));
        check("^0.0", a("0.0.0", "0.0.9"), a("0.1.0"));
        check("^0", a("0.0.0", "0.9.9"), a("1.0.0"));
        check("^1", a("1.0.0", "1.4.0"), a("2.0.0", "0.9.9"));
        check("^0.2", a("0.2.0", "0.2.5"), a("0.3.0"));
    }

    @Test
    void tilde() {
        check("~1.2.3", a("1.2.3", "1.2.99"), a("1.3.0", "1.2.2"));
        check("~1.2", a("1.2.0", "1.2.5"), a("1.3.0"));
        check("~1", a("1.0.0", "1.9.9"), a("2.0.0", "0.9.0"));
        check("~0.2.3", a("0.2.3", "0.2.8"), a("0.3.0"));
    }

    @Test
    void comparatorsWithPartials() {
        check(">=1.2.3", a("1.2.3", "9.0.0"), a("1.2.2"));
        check(">1.2.3", a("1.2.4"), a("1.2.3"));
        check("<1.2.3", a("1.2.2", "0.0.1"), a("1.2.3"));
        check("<=1.2.3", a("1.2.3", "1.0.0"), a("1.2.4"));
        check(">=1.2", a("1.2.0", "3.0.0"), a("1.1.9"));
        check(">1.2", a("1.3.0", "2.0.0"), a("1.2.9", "1.2.0"));
        check("<1.2", a("1.1.9"), a("1.2.0"));
        check("<=1.2", a("1.2.9"), a("1.3.0"));
        check(">1", a("2.0.0"), a("1.9.9"));
        check("<=1", a("1.9.9"), a("2.0.0"));
        check(">*", a(), a("0.0.0", "1.0.0"));
        check("<*", a(), a("0.0.0", "1.0.0"));
        check(">=*", a("0.0.0"), a());
    }

    @Test
    void andOrAndSpacedOperators() {
        check(">=1.2.0 <1.5.0", a("1.2.0", "1.4.9"), a("1.1.9", "1.5.0"));
        check(">= 1.2.0   < 1.5.0", a("1.3.0"), a("1.5.0"));
        check("^1.0.0 || ^3.0.0", a("1.5.0", "3.1.0"), a("2.0.0", "4.0.0"));
        check("1.2.3 || >=2.0.0 <2.2.0 ||", a("1.2.3", "2.1.0", "0.0.1", "7.7.7"), a());
        check(">=1.0.0 <2.0.0 || >=1.5.0", a("1.0.0", "1.9.0", "2.0.0", "5.0.0"), a("0.9.0"));
    }

    @Test
    void prereleaseRules() {
        // a prerelease only matches when an explicit comparator of the same alternative has the same x.y.z and a prerelease
        check("^1.2.3-beta.2", a("1.2.3-beta.2", "1.2.3-beta.11", "1.2.3", "1.5.0"), a("1.2.3-beta.1", "1.2.4-beta.1", "2.0.0"));
        check(">=1.2.3-beta.2 <2.0.0", a("1.2.3-rc.1"), a("1.2.4-rc.1", "2.0.0-rc.1"));
        check("^1.0.0", a("1.0.0"), a("1.0.0-rc.1", "1.5.0-rc.1", "2.0.0-alpha"));
        check("~1.2.0", a("1.2.5"), a("1.3.0-alpha", "1.2.5-alpha"));
        check("<2.0.0", a("1.9.9"), a("2.0.0-alpha", "1.9.9-alpha"));
        check("1.2.3-rc.1", a("1.2.3-rc.1"), a("1.2.3-rc.2", "1.2.3"));
        // the allowance is per alternative
        check("^1.2.3-beta.2 || ^2.0.0", a("1.2.3-beta.3", "2.5.0"), a("2.5.0-beta.1", "2.0.0-alpha"));
    }

    @Test
    void rejectsGarbage() {
        for (String s : new String[] {"abc", "1.2.3.4", ">=", "^", "~~1.0.0", "1.2.3 -", "01.2.3", "1.x.3", ">>1.0.0", "=<1.0.0", "1.2.3-"}) {
            assertThrows(IllegalArgumentException.class, () -> Constraint.parse(s), "input: '" + s + "'");
        }
        assertThrows(NullPointerException.class, () -> Constraint.parse(null));
    }

    @Test
    void toStringKeepsTrimmedSource() {
        assertEquals("^1.2.3 || 2.x", Constraint.parse("  ^1.2.3 || 2.x ").toString());
    }
}
