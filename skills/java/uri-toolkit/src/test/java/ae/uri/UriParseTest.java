package ae.uri;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UriParseTest {
    @Test
    void splitsAllComponents() {
        Uri u = Uri.parse("foo://user:pw@Example.com:8042/over/there?name=ferret#nose");
        assertEquals("foo", u.scheme());
        assertEquals("user:pw", u.userInfo());
        assertEquals("Example.com", u.host());
        assertEquals(8042, u.port());
        assertEquals("/over/there", u.path());
        assertEquals("name=ferret", u.query());
        assertEquals("nose", u.fragment());
    }

    @Test
    void absentVersusEmptyComponents() {
        Uri a = Uri.parse("http://h/p");
        assertNull(a.query());
        assertNull(a.fragment());
        assertNull(a.userInfo());
        assertEquals(-1, a.port());
        Uri b = Uri.parse("http://@h:/p?#");
        assertEquals("", b.userInfo());
        assertEquals(-1, b.port()); // empty port is allowed and equals absent after parse
        assertEquals("", b.query());
        assertEquals("", b.fragment());
        assertEquals("http://@h/p?#", b.toString()); // empty port is dropped on recomposition
        assertNotEquals(a, Uri.parse("http://h/p?"));
    }

    @Test
    void parsesNonHierarchicalAndRelative() {
        Uri m = Uri.parse("mailto:John.Doe@example.com");
        assertEquals("mailto", m.scheme());
        assertNull(m.host());
        assertEquals("John.Doe@example.com", m.path());
        Uri n = Uri.parse("urn:oasis:names:specification:docbook:dtd:xml:4.1.2");
        assertEquals("urn", n.scheme());
        Uri rel = Uri.parse("//host/x");
        assertNull(rel.scheme());
        assertEquals("host", rel.host());
        Uri colon = Uri.parse("./a:b");
        assertNull(colon.scheme());
        assertEquals("./a:b", colon.path());
        Uri file = Uri.parse("file:///etc/hosts");
        assertEquals("", file.host());
        assertEquals("/etc/hosts", file.path());
    }

    @Test
    void roundTripsExactly() {
        String[] ok = {
            "http://a/b/c/d;p?q", "", "?", "#", "//", "///", "a:", "a:/", "a:?#", "http://[::1]:80/", "http://[2001:db8::7]/c=GB?objectClass?one",
            "ldap://[2001:db8::7]/c=GB?objectClass?one", "http://1.2.3.4:0/", "tel:+1-816-555-1212", "news:comp.infosystems.www.servers.unix",
            "http://a/%7Efoo/%e2%82%ac", "http://h/?a=b&c=d%20e#f%41", "x+y-z.1:p", "http://[v7.fe80::a+en1]/", "http://[::ffff:1.2.3.4]/",
            "http://u@h/", "http://h.example:65535", "a/b/c", "../x", "./", "//h?q", "http://a!$&'()*+,;=b/",
        };
        for (String s : ok) assertEquals(s, Uri.parse(s).toString(), s);
    }

    @Test
    void rejectsMalformedInputsWithControls() {
        // each rejection has a control that differs only by the defect
        String[][] pairs = {
            {"http://h/a b", "http://h/a%20b"},
            {"http://h/%zz", "http://h/%7a"},
            {"http://h/%7", "http://h/%77"},
            {"http://h:80a/", "http://h:80/"},
            {"http://h:65536/", "http://h:65535/"},
            {"http://h:-1/", "http://h:1/"},
            {"1http://h/", "h1ttp://h/"},
            {"ht tp://h/", "ht-tp://h/"},
            {"http://[::1/", "http://[::1]/"},
            {"http://[:::1]/", "http://[::1]/"},
            {"http://[1::2::3]/", "http://[1::23]/"},
            {"http://[1:2:3:4:5:6:7:8:9]/", "http://[1:2:3:4:5:6:7:8]/"},
            {"http://[12345::]/", "http://[1234::]/"},
            {"http://[1:2:3:4:5:6:7]/", "http://[1:2:3:4:5:6:7::]/"},
            {"http://[::1.2.3.256]/", "http://[::1.2.3.255]/"},
            {"http://[g::1]/", "http://[f::1]/"},
            {"http://[v1.]/", "http://[v1.a]/"},
            {"http://h/<>", "http://h/%3C%3E"},
            {"http://h/\u00e9", "http://h/%C3%A9"},
            {"http://h/p#a#b", "http://h/p#a%23b"},
            {"http://h/p?a b", "http://h/p?a%20b"},
            {"http://us er@h/", "http://us%20er@h/"},
            {"http://h[a]/", "http://h/"},
            {"http://a@b@c/", "http://a%40b@c/"},
            {":foo", "/:foo"},
            {"http://h/[x]", "http://h/%5Bx%5D"},
            {"http://h/\\", "http://h/%5C"},
            {"http://h/\n", "http://h/%0A"},
        };
        for (String[] p : pairs) {
            assertThrows(IllegalArgumentException.class, () -> Uri.parse(p[0]), "should reject " + p[0]);
            assertEquals(p[1], Uri.parse(p[1]).toString(), "control " + p[1]);
        }
    }

    @Test
    void questionMarkAndSlashAreLegalInQueryAndFragment() {
        Uri u = Uri.parse("http://h/p?a/b?c#d/e?f");
        assertEquals("a/b?c", u.query());
        assertEquals("d/e?f", u.fragment());
    }

    @Test
    void ipv6DetailsAcceptedPerRfc() {
        for (String h : new String[] {"::", "::1", "1::", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:1.2.3.4", "::1.2.3.4", "1:2:3:4:5::8", "ABCD:ef01::", "1::7:8"})
            assertEquals("http://[" + h + "]/", Uri.parse("http://[" + h + "]/").toString(), h);
        for (String h : new String[] {"1:2:3:4:5:6:7:8::", "1:2:3:4:5:6:7:8:1.2.3.4", "1.2.3.4", "1::2:3:4:5:6:7:8", "1:2:3:4:5:6:7:1.2.3.4", "1.2.3.4::", ":1", "1:", ""})
            assertThrows(IllegalArgumentException.class, () -> Uri.parse("http://[" + h + "]/"), h);
    }

    @Test
    void ipv4OctetsRejectLeadingZeros() {
        assertEquals("http://[::1.2.3.0]/", Uri.parse("http://[::1.2.3.0]/").toString());
        assertThrows(IllegalArgumentException.class, () -> Uri.parse("http://[::1.2.3.01]/"));
        assertThrows(IllegalArgumentException.class, () -> Uri.parse("http://[::1.2.3.00]/"));
    }

    @Test
    void portBounds() {
        assertEquals(65535, Uri.parse("http://h:65535/").port());
        assertEquals(80, Uri.parse("http://h:0080/").port());
        assertThrows(IllegalArgumentException.class, () -> Uri.parse("http://h:" + "9".repeat(30) + "/"));
    }

    @Test
    void equalityIsComponentWise() {
        assertEquals(Uri.parse("http://h/p?q#f"), Uri.parse("http://h/p?q#f"));
        assertEquals(Uri.parse("http://h/p?q#f").hashCode(), Uri.parse("http://h/p?q#f").hashCode());
        assertNotEquals(Uri.parse("http://h/p"), Uri.parse("http://H/p")); // no implicit normalisation
        assertNotEquals(Uri.parse("http://h/p"), Uri.parse("http://h:80/p"));
        assertNotEquals(Uri.parse("http://h/p"), "http://h/p");
    }

    @Test
    void nullInputRejected() {
        assertThrows(NullPointerException.class, () -> Uri.parse(null));
    }
}
