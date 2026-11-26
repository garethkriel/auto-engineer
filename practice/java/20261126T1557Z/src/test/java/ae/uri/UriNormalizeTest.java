package ae.uri;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UriNormalizeTest {
    private static String n(String s) {
        return Uri.parse(s).normalize().toString();
    }

    @Test
    void tableOfNormalisations() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("HTTP://User@EXAMPLE.Com/", "http://User@example.com/");
        m.put("http://example.com", "http://example.com/");
        m.put("http://example.com:80/", "http://example.com/");
        m.put("http://example.com:8080/", "http://example.com:8080/");
        m.put("https://example.com:443/x", "https://example.com/x");
        m.put("https://example.com:80/x", "https://example.com:80/x");
        m.put("http://example.com:443/x", "http://example.com:443/x");
        m.put("ftp://h:21", "ftp://h/");
        m.put("ws://h:80/", "ws://h/");
        m.put("wss://h:443/", "wss://h/");
        m.put("foo://h:80", "foo://h:80");
        m.put("http://h/%7euser/%e2%82%ac", "http://h/~user/%E2%82%AC");
        m.put("http://h/a%2fb", "http://h/a%2Fb");
        m.put("http://h/%41%5a%61%7A%30%39%2D%2e%5F%7E", "http://h/AZaz09-._~");
        m.put("http://h/a/./b/../c", "http://h/a/c");
        m.put("http://h/a/b/../../../c", "http://h/c");
        m.put("http://h/a/b/..", "http://h/a/");
        m.put("http://h/?q=%7e%2f#f%7e", "http://h/?q=~%2F#f~");
        m.put("http://H%41/", "http://ha/");
        m.put("mailto:A@B.com", "mailto:A@B.com");
        m.put("a/./b/../c", "a/c");
        m.put("HTTP://[ABCD::1]:80", "http://[abcd::1]/");
        m.put("http://h:/p", "http://h/p");
        m.put("file:///a/../b", "file:///b");
        m.put("http://h/?", "http://h/?");
        m.put("http://h/#", "http://h/#");
        m.put("/a/%2e%2E/b", "/b");
        for (var e : m.entrySet()) assertEquals(e.getValue(), n(e.getKey()), e.getKey());
    }

    @Test
    void decodedUnreservedDotsParticipateInDotRemoval() {
        // %2e is the unreserved '.', so it must be decoded BEFORE dot segments are removed
        assertEquals("http://h/b", n("http://h/a/%2e%2e/b"));
        assertEquals("http://h/a/b", n("http://h/a/%2e/b"));
        // control: an encoded slash is NOT unreserved and must survive
        assertEquals("http://h/a%2F..%2Fb", n("http://h/a%2f..%2fb"));
    }

    @Test
    void reservedPercentEscapesAreNotDecoded() {
        for (String c : new String[] {"%3A", "%2F", "%3F", "%23", "%5B", "%5D", "%40", "%21", "%24", "%26", "%27", "%28", "%29", "%2A", "%2B", "%2C", "%3B", "%3D", "%25", "%20"})
            assertEquals("http://h/x" + c, n("http://h/x" + c.toLowerCase()), c);
    }

    @Test
    void normalizeIsIdempotentAndKeepsEquivalentFormsEqual() {
        String[] xs = {"HTTP://A.com:80/%7e/./x/../y?%7a#%7b", "http://h", "a/../..", "x:/%2e%2e/%2E/z", "//H:80/a"};
        for (String s : xs) {
            Uri once = Uri.parse(s).normalize();
            assertEquals(once, once.normalize(), s);
        }
        assertEquals(Uri.parse("http://EXAMPLE.com:80/a/../b%7e").normalize(), Uri.parse("http://example.com/b~").normalize());
    }

    @Test
    void hostPercentOctetsDecodeOnlyWhenUnreserved() {
        // control pair: %61 -> a (unreserved), %C3%A9 stays (not unreserved) with uppercase hex
        assertEquals("http://a/", n("http://%61/"));
        assertEquals("http://%C3%A9/", n("http://%c3%a9/"));
    }
}
