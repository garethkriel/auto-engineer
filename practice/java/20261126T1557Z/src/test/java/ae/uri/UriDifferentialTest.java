package ae.uri;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Seeded random checks against java.net.URI and algebraic properties. */
class UriDifferentialTest {
    private static final String[] SEGS = {"a", "b", "c", "x.y", "..", ".", "", "p;q=1", "%41", "%7e"};

    private static String path(Random r, boolean abs) {
        StringBuilder sb = new StringBuilder();
        int n = 1 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            if (i > 0 || abs) sb.append('/');
            sb.append(SEGS[r.nextInt(SEGS.length)]);
        }
        return sb.toString();
    }

    static String gen(Random r, boolean needScheme) {
        StringBuilder sb = new StringBuilder();
        boolean scheme = needScheme || r.nextInt(2) == 0;
        boolean auth = scheme ? r.nextInt(3) > 0 : r.nextInt(4) == 0;
        if (scheme) sb.append(new String[] {"http", "https", "ftp", "x-y"}[r.nextInt(4)]).append(':');
        if (auth) {
            sb.append("//");
            if (r.nextInt(4) == 0) sb.append("u%41:p@");
            sb.append(new String[] {"h", "Ex.com", "1.2.3.4", "[::1]", "a-b.c"}[r.nextInt(5)]);
            if (r.nextInt(3) == 0) sb.append(':').append(r.nextInt(65536));
            if (r.nextInt(5) > 0) sb.append(path(r, true));
        } else {
            int k = r.nextInt(3);
            if (k == 0) sb.append(path(r, true));
            else if (k == 1 && !scheme) sb.append(path(r, false));
            else if (scheme) sb.append("/").append(path(r, false));
        }
        if (r.nextInt(3) == 0) sb.append('?').append(new String[] {"", "a=1", "a=b/./c", "%7e&z"}[r.nextInt(4)]);
        if (r.nextInt(4) == 0) sb.append('#').append(new String[] {"", "f", "a/../b"}[r.nextInt(3)]);
        return sb.toString();
    }

    @Test
    void generatorProducesOnlyValidUris() {
        Random r = new Random(11);
        for (int i = 0; i < 2000; i++) {
            String s = gen(r, r.nextBoolean());
            assertEquals(s, Uri.parse(s).toString(), s);
        }
    }

    @Test
    void componentsMatchJavaNetUri() {
        Random r = new Random(5);
        int compared = 0;
        for (int i = 0; i < 2000; i++) {
            String s = gen(r, false);
            java.net.URI j;
            try {
                j = new java.net.URI(s);
            } catch (java.net.URISyntaxException e) {
                continue; // JDK is stricter about some forms such as paths with empty authority
            }
            Uri u = Uri.parse(s);
            assertEquals(j.getScheme(), u.scheme(), s);
            assertEquals(j.getRawQuery(), u.query(), s);
            assertEquals(j.getRawFragment(), u.fragment(), s);
            if (j.getRawAuthority() != null) assertEquals(j.getRawPath(), u.path(), s);
            if (j.getRawUserInfo() != null) assertEquals(j.getRawUserInfo(), u.userInfo(), s);
            if (j.getPort() != -1) assertEquals(j.getPort(), u.port(), s);
            compared++;
        }
        assertTrue(compared > 1200, "compared=" + compared);
    }

    @Test
    void resolveMatchesJavaWhenNoRootClamping() {
        Random r = new Random(23);
        int compared = 0, skipped = 0;
        for (int i = 0; i < 12000; i++) {
            String b = gen(r, true);
            String ref = gen(r, false);
            if (Uri.parse(b).path().isEmpty() && Uri.parse(b).host() != null) continue; // JDK merges differently
            if (ref.startsWith("#") || ref.isEmpty()) continue;
            // RFC 5.2.2 copies the base path untouched for an empty ref path; the JDK normalises it
            if (Uri.parse(ref).path().isEmpty() && Uri.parse(ref).host() == null && Uri.parse(ref).scheme() == null) continue;
            // the JDK collapses empty path segments ("//") while resolving; RFC 3986 keeps them
            if (Uri.parse(b).path().contains("//") || Uri.parse(ref).path().contains("//")) continue;
            // the JDK drops an empty authority ("///x"); RFC 3986 keeps host "" (tested in UriParseTest)
            if (Uri.parse(ref).host() != null && Uri.parse(ref).host().isEmpty()) continue;
            if (Uri.parse(b).host() != null && Uri.parse(b).host().isEmpty()) continue;
            // the JDK returns an absolute ref untouched; RFC 5.2.2 removes its dot segments (tested separately)
            if (Uri.parse(ref).scheme() != null) continue;
            String mine = Uri.parse(b).resolve(Uri.parse(ref)).toString();
            java.net.URI j;
            try {
                j = new java.net.URI(b).resolve(new java.net.URI(ref));
            } catch (Exception e) {
                skipped++;
                continue;
            }
            if (j.getRawPath() != null && (j.getRawPath().contains("/../") || j.getRawPath().endsWith("/..")
                    || j.getRawPath().contains("/./") || j.getRawPath().endsWith("/."))) {
                skipped++;
                continue; // JDK keeps ".." above the root, RFC 3986 clamps them
            }
            if (Uri.parse(b).host() != null && Uri.parse(ref).host() == null && Uri.parse(ref).scheme() == null
                    && !Uri.parse(ref).path().isEmpty() && j.getRawPath() != null && j.getRawPath().isEmpty()) continue;
            assertEquals(j.toString(), mine, "base=" + b + " ref=" + ref);
            compared++;
        }
        assertTrue(compared > 800, "compared=" + compared + " skipped=" + skipped);
    }

    @Test
    void resolveOfNormalizedAbsoluteRefIsNormalizedRef() {
        Random r = new Random(31);
        for (int i = 0; i < 1500; i++) {
            Uri base = Uri.parse(gen(r, true));
            Uri ref = Uri.parse(gen(r, true));
            // ref has a scheme: resolve(base, ref) == ref with dot segments removed
            Uri res = base.resolve(ref);
            assertEquals(ref.scheme(), res.scheme());
            String expect = Uri.removeDotSegments(ref.path());
            if (ref.host() == null && expect.startsWith("//")) expect = "/." + expect; // keeps the text re-parseable
            assertEquals(expect, res.path(), ref.toString());
            assertEquals(ref.query(), res.query());
            assertEquals(ref.fragment(), res.fragment());
        }
    }

    @Test
    void relativizeRoundTrips() {
        Random r = new Random(41);
        int relative = 0;
        for (int i = 0; i < 4000; i++) {
            Uri b = Uri.parse(gen(r, true)).normalize();
            Uri t;
            if (r.nextInt(3) == 0) {
                t = Uri.parse(gen(r, true)).normalize();
            } else { // same scheme and authority, different path
                String authority = b.toString().replaceAll("^([^/?#]*:)?(//[^/?#]*)?.*$", "$1$2");
                String s = authority + (b.host() != null ? path(r, true) : "/" + path(r, false));
                if (r.nextInt(3) == 0) s += "?a=1";
                if (r.nextInt(3) == 0) s += "#f";
                t = Uri.parse(s).normalize();
            }
            if (b.host() == null && b.path().isEmpty()) continue;
            Uri rel = b.relativize(t);
            assertEquals(t, b.resolve(rel), "base=" + b + " target=" + t + " rel=" + rel);
            if (rel.scheme() == null) relative++;
        }
        assertTrue(relative > 1200, "relative=" + relative);
    }
}
