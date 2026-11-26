package ae.uri;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class UriResolveTest {
    private static final Uri BASE = Uri.parse("http://a/b/c/d;p?q");

    private static String r(String ref) {
        return BASE.resolve(Uri.parse(ref)).toString();
    }

    @Test
    void rfc3986NormalExamples() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("g:h", "g:h");
        m.put("g", "http://a/b/c/g");
        m.put("./g", "http://a/b/c/g");
        m.put("g/", "http://a/b/c/g/");
        m.put("/g", "http://a/g");
        m.put("//g", "http://g");
        m.put("?y", "http://a/b/c/d;p?y");
        m.put("g?y", "http://a/b/c/g?y");
        m.put("#s", "http://a/b/c/d;p?q#s");
        m.put("g#s", "http://a/b/c/g#s");
        m.put("g?y#s", "http://a/b/c/g?y#s");
        m.put(";x", "http://a/b/c/;x");
        m.put("g;x", "http://a/b/c/g;x");
        m.put("g;x?y#s", "http://a/b/c/g;x?y#s");
        m.put("", "http://a/b/c/d;p?q");
        m.put(".", "http://a/b/c/");
        m.put("./", "http://a/b/c/");
        m.put("..", "http://a/b/");
        m.put("../", "http://a/b/");
        m.put("../g", "http://a/b/g");
        m.put("../..", "http://a/");
        m.put("../../", "http://a/");
        m.put("../../g", "http://a/g");
        for (var e : m.entrySet()) assertEquals(e.getValue(), r(e.getKey()), "ref=" + e.getKey());
    }

    @Test
    void rfc3986AbnormalExamples() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("../../../g", "http://a/g");
        m.put("../../../../g", "http://a/g");
        m.put("/./g", "http://a/g");
        m.put("/../g", "http://a/g");
        m.put("g.", "http://a/b/c/g.");
        m.put(".g", "http://a/b/c/.g");
        m.put("g..", "http://a/b/c/g..");
        m.put("..g", "http://a/b/c/..g");
        m.put("./../g", "http://a/b/g");
        m.put("./g/.", "http://a/b/c/g/");
        m.put("g/./h", "http://a/b/c/g/h");
        m.put("g/../h", "http://a/b/c/h");
        m.put("g;x=1/./y", "http://a/b/c/g;x=1/y");
        m.put("g;x=1/../y", "http://a/b/c/y");
        m.put("g?y/./x", "http://a/b/c/g?y/./x");
        m.put("g?y/../x", "http://a/b/c/g?y/../x");
        m.put("g#s/./x", "http://a/b/c/g#s/./x");
        m.put("g#s/../x", "http://a/b/c/g#s/../x");
        m.put("http:g", "http:g");
        for (var e : m.entrySet()) assertEquals(e.getValue(), r(e.getKey()), "ref=" + e.getKey());
    }

    @Test
    void emptyBasePathWithAuthorityMergesToRoot() {
        // RFC 3986 5.2.3: base has authority and empty path -> "/" + ref path
        assertEquals("http://h/x", Uri.parse("http://h").resolve(Uri.parse("x")).toString());
        assertEquals("http://h/x", Uri.parse("http://h?q").resolve(Uri.parse("x")).toString());
        // control: base without authority merges against the last slash only
        assertEquals("s:/a/x", Uri.parse("s:/a/b").resolve(Uri.parse("x")).toString());
    }

    @Test
    void baseWithoutSchemeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Uri.parse("a/b").resolve(Uri.parse("x")));
        assertEquals("s:/x", Uri.parse("s:/y").resolve(Uri.parse("x")).toString());
    }

    @Test
    void removeDotSegmentsRfcExamples() {
        assertEquals("/a/g", Uri.removeDotSegments("/a/b/c/./../../g"));
        assertEquals("mid/6", Uri.removeDotSegments("mid/content=5/../6"));
        assertEquals("/", Uri.removeDotSegments("/.."));
        assertEquals("/", Uri.removeDotSegments("/."));
        assertEquals("", Uri.removeDotSegments(""));
        assertEquals("/a/", Uri.removeDotSegments("/a/b/.."));
        assertEquals("a/b", Uri.removeDotSegments("a/b"));
        assertEquals("/a/b/", Uri.removeDotSegments("/a/b/"));
        assertEquals("/a//c", Uri.removeDotSegments("/a//./c"));
        assertEquals("", Uri.removeDotSegments("../.."));
    }

    @Test
    void resolveDropsBaseFragment() {
        assertEquals("http://a/g", Uri.parse("http://a/x#frag").resolve(Uri.parse("/g")).toString());
        assertEquals("http://a/x?q", Uri.parse("http://a/x?q#frag").resolve(Uri.parse("")).toString());
    }

    @Test
    void relativizeResolvesBack() {
        String[][] cases = {
            {"http://a/b/c/d;p?q", "http://a/b/c/g"},
            {"http://a/b/c/d;p?q", "http://a/b/g"},
            {"http://a/b/c/d;p?q", "http://a/g"},
            {"http://a/b/c/d;p?q", "http://a/b/c/d;p?q"},
            {"http://a/b/c/d;p?q", "http://a/b/c/d;p?z#f"},
            {"http://a/b/c/d;p?q", "http://a/b/c/d;p?q#f"},
            {"http://a/b/c/d", "http://a/b/c/"},
            {"http://a/b/c/d", "http://a/b/c/x:y"},
            {"http://a/b/c/d", "http://a/b/c//x"},
            {"http://a/b/c/d", "http://a"},
            {"http://a/b/c/d", "http://z/q"},
            {"http://a/b/c/d", "https://a/b/c/d"},
        };
        for (String[] c : cases) {
            Uri b = Uri.parse(c[0]), t = Uri.parse(c[1]);
            Uri rel = b.relativize(t);
            assertEquals(t, b.resolve(rel), c[0] + " -> " + c[1] + " via " + rel);
        }
    }

    @Test
    void relativizeProducesShortForms() {
        Uri b = Uri.parse("http://a/b/c/d;p?q");
        assertEquals("g", b.relativize(Uri.parse("http://a/b/c/g")).toString());
        assertEquals("../g", b.relativize(Uri.parse("http://a/b/g")).toString());
        assertEquals("", b.relativize(Uri.parse("http://a/b/c/d;p?q")).toString());
        assertEquals("#f", b.relativize(Uri.parse("http://a/b/c/d;p?q#f")).toString());
        assertEquals("?z", b.relativize(Uri.parse("http://a/b/c/d;p?z")).toString());
        assertEquals("http://z/q", Uri.parse("http://a/b").relativize(Uri.parse("http://z/q")).toString());
        // first segment containing ':' must not look like a scheme
        assertEquals("./x:y", Uri.parse("http://a/b/c/d").relativize(Uri.parse("http://a/b/c/x:y")).toString());
        assertEquals("http://a", Uri.parse("http://a/b/c/d").relativize(Uri.parse("http://a")).toString());
    }

    @Test
    void relativizeLeavesDotSegmentPathsAlone() {
        // base dir [b, .., c] vs target dir [b] would give "../../x", which resolves to /x, not /b/x
        Uri b = Uri.parse("http://a/b/../c/d");
        Uri t = Uri.parse("http://a/b/x");
        assertEquals(t, b.relativize(t));
        assertEquals(Uri.parse("http://a/b/./x"), Uri.parse("http://a/c/d").relativize(Uri.parse("http://a/b/./x")));
    }
}
