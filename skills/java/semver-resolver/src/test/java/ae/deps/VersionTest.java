package ae.deps;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionTest {
    @Test
    void parsesAndPrints() {
        Version v = Version.parse("1.20.3-beta.2");
        assertEquals(1, v.major());
        assertEquals(20, v.minor());
        assertEquals(3, v.patch());
        assertEquals(List.of("beta", "2"), v.pre());
        assertEquals("1.20.3-beta.2", v.toString());
        assertEquals("0.0.0", Version.parse("0.0.0").toString());
    }

    @Test
    void rejectsBadInput() {
        for (String s : new String[] {"", "1", "1.2", "1.2.3.4", "01.2.3", "1.02.3", "1.2.3-", "1.2.3-a..b",
            "1.2.3-01", "1.2.3+build", "a.b.c", "-1.0.0", "1.2.3-a_b", "99999999999.0.0", " 1.2.3"}) {
            assertThrows(IllegalArgumentException.class, () -> Version.parse(s), "input: '" + s + "'");
        }
        assertThrows(NullPointerException.class, () -> Version.parse(null));
    }

    @Test
    void semverSpecPrecedenceChain() {
        // the ordering example from the semver 2.0.0 specification
        String[] ascending = {"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "2.0.0", "2.1.0", "2.1.1"};
        for (int i = 0; i < ascending.length; i++) {
            for (int j = 0; j < ascending.length; j++) {
                int expected = Integer.compare(i, j);
                int actual = Integer.signum(Version.parse(ascending[i]).compareTo(Version.parse(ascending[j])));
                assertEquals(expected, actual, ascending[i] + " vs " + ascending[j]);
            }
        }
    }

    @Test
    void numericIdentifiersCompareNumericallyNotLexically() {
        assertTrue(Version.parse("1.0.0-2").compareTo(Version.parse("1.0.0-10")) < 0);
        assertTrue(Version.parse("1.0.0-99999999999999999999").compareTo(Version.parse("1.0.0-100000000000000000000")) < 0);
        assertTrue(Version.parse("1.10.0").compareTo(Version.parse("1.9.0")) > 0);
    }

    @Test
    void equalsHashCodeAndSorting() {
        assertEquals(Version.parse("1.2.3-a"), Version.parse("1.2.3-a"));
        assertEquals(Version.parse("1.2.3-a").hashCode(), Version.parse("1.2.3-a").hashCode());
        assertNotEquals(Version.parse("1.2.3-a"), Version.parse("1.2.3"));
        List<Version> vs = new ArrayList<>();
        for (String s : new String[] {"2.0.0", "1.0.0-rc.1", "1.0.0", "1.0.0-alpha"}) vs.add(Version.parse(s));
        Collections.sort(vs);
        assertEquals("[1.0.0-alpha, 1.0.0-rc.1, 1.0.0, 2.0.0]", vs.toString());
    }

    @Test
    void preListIsImmutable() {
        Version v = Version.parse("1.0.0-a");
        assertThrows(UnsupportedOperationException.class, () -> v.pre().add("x"));
    }
}
