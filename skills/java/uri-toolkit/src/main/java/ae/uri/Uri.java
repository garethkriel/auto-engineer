package ae.uri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable RFC 3986 URI reference. Components follow the RFC: {@code null} means absent, the empty
 * string means present but empty. An authority is present exactly when {@link #host()} is non-null.
 */
public final class Uri {
    private static final Set<String> HIERARCHICAL = Set.of("http", "https", "ws", "wss", "ftp");

    private final String scheme;
    private final String userInfo;
    private final String host;
    private final int port;
    private final String path;
    private final String query;
    private final String fragment;

    private Uri(String scheme, String userInfo, String host, int port, String path, String query, String fragment) {
        this.scheme = scheme;
        this.userInfo = userInfo;
        this.host = host;
        this.port = port;
        this.path = path;
        this.query = query;
        this.fragment = fragment;
    }

    public String scheme() { return scheme; }
    public String userInfo() { return userInfo; }
    public String host() { return host; }
    /** The port, or -1 when absent. */
    public int port() { return port; }
    public String path() { return path; }
    public String query() { return query; }
    public String fragment() { return fragment; }

    public QueryParams queryParams() {
        return query == null ? QueryParams.of() : QueryParams.parse(query);
    }

    /** Replaces the query; an empty parameter list removes it. */
    public Uri withQuery(QueryParams q) {
        String s = q.toString();
        return new Uri(scheme, userInfo, host, port, path, s.isEmpty() ? null : s, fragment);
    }

    // ------------------------------------------------------------------ parsing

    /** Strict RFC 3986 parser. Throws {@link IllegalArgumentException} on any syntax error. */
    public static Uri parse(String s) {
        Objects.requireNonNull(s);
        int n = s.length();
        for (int i = 0; i < n; i++) if (s.charAt(i) >= 128 || s.charAt(i) < 33 || s.charAt(i) == 127) bad(s, "illegal character at " + i);
        int i = 0;
        String scheme = null;
        int firstDelim = firstOf(s, 0, "/?#");
        int colon = s.indexOf(':');
        if (colon >= 0 && colon < firstDelim) {
            scheme = s.substring(0, colon);
            checkScheme(s, scheme);
            i = colon + 1;
        }
        String userInfo = null, host = null;
        int port = -1;
        if (s.startsWith("//", i)) {
            int end = firstOf(s, i + 2, "/?#");
            String auth = s.substring(i + 2, end);
            int at = auth.indexOf('@');
            if (at >= 0) {
                userInfo = auth.substring(0, at);
                checkChars(s, userInfo, true, false, "user info");
                auth = auth.substring(at + 1);
            }
            String portText = null;
            if (auth.startsWith("[")) {
                int close = auth.indexOf(']');
                if (close < 0) bad(s, "unterminated IP literal");
                checkIpLiteral(s, auth.substring(1, close));
                host = auth.substring(0, close + 1);
                String rest = auth.substring(close + 1);
                if (!rest.isEmpty()) {
                    if (rest.charAt(0) != ':') bad(s, "garbage after IP literal");
                    portText = rest.substring(1);
                }
            } else {
                int c = auth.lastIndexOf(':');
                if (c >= 0) {
                    portText = auth.substring(c + 1);
                    auth = auth.substring(0, c);
                }
                checkChars(s, auth, true, false, "host");
                host = auth;
            }
            if (portText != null && !portText.isEmpty()) port = parsePort(s, portText);
            i = end;
        }
        int hash = s.indexOf('#', i);
        int q = s.indexOf('?', i);
        if (hash >= 0 && q > hash) q = -1;
        int pathEnd = q >= 0 ? q : hash >= 0 ? hash : n;
        String path = s.substring(i, pathEnd);
        checkChars(s, path, true, true, "path");
        String query = null, fragment = null;
        if (q >= 0) {
            query = s.substring(q + 1, hash >= 0 ? hash : n);
            checkChars(s, query, true, true, "query", '?');
        }
        if (hash >= 0) {
            fragment = s.substring(hash + 1);
            checkChars(s, fragment, true, true, "fragment", '?');
        }
        if (host == null && scheme == null && firstSegmentHasColon(path)) bad(s, "colon in first segment of relative path");
        return new Uri(scheme, userInfo, host, port, path, query, fragment);
    }

    private static int firstOf(String s, int from, String delims) {
        for (int i = from; i < s.length(); i++) if (delims.indexOf(s.charAt(i)) >= 0) return i;
        return s.length();
    }

    private static boolean firstSegmentHasColon(String path) {
        int slash = path.indexOf('/');
        return (slash < 0 ? path : path.substring(0, slash)).indexOf(':') >= 0;
    }

    private static void bad(String s, String why) {
        throw new IllegalArgumentException("invalid URI \"" + s + "\": " + why);
    }

    private static void checkScheme(String s, String scheme) {
        if (scheme.isEmpty() || !Chars.isAlpha(scheme.charAt(0))) bad(s, "bad scheme");
        for (int i = 1; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!(Chars.isAlpha(c) || Chars.isDigit(c) || c == '+' || c == '-' || c == '.')) bad(s, "bad scheme");
        }
    }

    /** Checks pchar-style characters (plus '/' when {@code slash}, plus the extras) and %HH triplets. */
    private static void checkChars(String whole, String part, boolean pct, boolean slash, String what, char... extra) {
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '%') {
                if (i + 2 >= part.length() || Chars.hexValue(part.charAt(i + 1)) < 0 || Chars.hexValue(part.charAt(i + 2)) < 0) {
                    bad(whole, "bad percent escape in " + what);
                }
                i += 2;
                continue;
            }
            boolean ok = what.equals("host") || what.equals("user info")
                    ? Chars.isUnreserved(c) || Chars.isSubDelim(c) || (what.equals("user info") && c == ':')
                    : Chars.isPcharLiteral(c) || (slash && c == '/');
            for (char e : extra) if (c == e) ok = true;
            if (!ok) bad(whole, "illegal character '" + c + "' in " + what);
        }
    }

    private static int parsePort(String s, String text) {
        for (int i = 0; i < text.length(); i++) if (!Chars.isDigit(text.charAt(i))) bad(s, "bad port");
        String t = text.replaceFirst("^0+(?=.)", "");
        if (t.length() > 5) bad(s, "port out of range");
        int p = Integer.parseInt(t);
        if (p > 65535) bad(s, "port out of range");
        return p;
    }

    private static void checkIpLiteral(String s, String lit) {
        if (lit.length() > 1 && (lit.charAt(0) == 'v' || lit.charAt(0) == 'V')) {
            int dot = lit.indexOf('.');
            if (dot < 2) bad(s, "bad IPvFuture");
            for (int i = 1; i < dot; i++) if (Chars.hexValue(lit.charAt(i)) < 0) bad(s, "bad IPvFuture");
            if (dot == lit.length() - 1) bad(s, "bad IPvFuture");
            for (int i = dot + 1; i < lit.length(); i++) {
                char c = lit.charAt(i);
                if (!(Chars.isUnreserved(c) || Chars.isSubDelim(c) || c == ':')) bad(s, "bad IPvFuture");
            }
            return;
        }
        int dc = lit.indexOf("::");
        if (dc >= 0 && lit.indexOf("::", dc + 1) >= 0) bad(s, "more than one '::' in IPv6 address");
        String left = dc < 0 ? lit : lit.substring(0, dc);
        String right = dc < 0 ? "" : lit.substring(dc + 2);
        int groups = countGroups(s, left, dc < 0);
        if (dc < 0) {
            if (groups != 8) bad(s, "IPv6 address needs 8 groups");
        } else {
            groups += countGroups(s, right, true);
            if (groups > 7) bad(s, "too many IPv6 groups");
        }
    }

    /** Counts 16-bit groups of a colon-separated run; an IPv4 tail (only when {@code tail}) counts two. */
    private static int countGroups(String s, String run, boolean tail) {
        if (run.isEmpty()) return 0;
        String[] g = run.split(":", -1);
        int count = 0;
        for (int i = 0; i < g.length; i++) {
            String x = g[i];
            if (x.indexOf('.') >= 0) {
                // an IPv4 tail only counts when it is the last group of the whole address
                if (!(tail && i == g.length - 1)) bad(s, "IPv4 part not at the end");
                checkIpv4(s, x);
                count += 2;
            } else {
                if (x.isEmpty() || x.length() > 4) bad(s, "bad IPv6 group");
                for (int k = 0; k < x.length(); k++) if (Chars.hexValue(x.charAt(k)) < 0) bad(s, "bad IPv6 group");
                count++;
            }
        }
        return count;
    }

    private static void checkIpv4(String s, String x) {
        String[] o = x.split("\\.", -1);
        if (o.length != 4) bad(s, "bad IPv4 address");
        for (String d : o) {
            if (d.isEmpty() || d.length() > 3 || (d.length() > 1 && d.charAt(0) == '0')) bad(s, "bad IPv4 octet");
            for (int i = 0; i < d.length(); i++) if (!Chars.isDigit(d.charAt(i))) bad(s, "bad IPv4 octet");
            if (Integer.parseInt(d) > 255) bad(s, "bad IPv4 octet");
        }
    }

    // ------------------------------------------------------------ recomposition

    /** RFC 3986 section 5.3. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) sb.append(scheme).append(':');
        if (host != null) {
            sb.append("//");
            if (userInfo != null) sb.append(userInfo).append('@');
            sb.append(host);
            if (port >= 0) sb.append(':').append(port);
        }
        sb.append(path);
        if (query != null) sb.append('?').append(query);
        if (fragment != null) sb.append('#').append(fragment);
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Uri u && Objects.equals(scheme, u.scheme) && Objects.equals(userInfo, u.userInfo)
                && Objects.equals(host, u.host) && port == u.port && path.equals(u.path)
                && Objects.equals(query, u.query) && Objects.equals(fragment, u.fragment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, userInfo, host, port, path, query, fragment);
    }

    /**
     * Builds a URI whose text re-parses to the same components: without an authority a path starting
     * with "//" gets a "/." prefix, and a scheme-less relative path whose first segment contains ':'
     * gets a "./" prefix.
     */
    private static Uri build(String scheme, String userInfo, String host, int port, String path, String query, String fragment) {
        if (host == null && path.startsWith("//")) path = "/." + path;
        if (host == null && scheme == null && firstSegmentHasColon(path)) path = "./" + path;
        return new Uri(scheme, userInfo, host, port, path, query, fragment);
    }

    // --------------------------------------------------------------- resolution

    /** RFC 3986 section 5.2.4. */
    public static String removeDotSegments(String input) {
        StringBuilder in = new StringBuilder(input);
        StringBuilder out = new StringBuilder();
        while (in.length() > 0) {
            String s = in.toString();
            if (s.startsWith("../")) {
                in.delete(0, 3);
            } else if (s.startsWith("./")) {
                in.delete(0, 2);
            } else if (s.startsWith("/./")) {
                in.delete(0, 2);
            } else if (s.equals("/.")) {
                in.replace(0, 2, "/");
            } else if (s.startsWith("/../")) {
                in.delete(0, 3);
                dropLastSegment(out);
            } else if (s.equals("/..")) {
                in.replace(0, 3, "/");
                dropLastSegment(out);
            } else if (s.equals(".") || s.equals("..")) {
                in.setLength(0);
            } else {
                int next = s.indexOf('/', 1);
                int end = next < 0 ? s.length() : next;
                out.append(s, 0, end);
                in.delete(0, end);
            }
        }
        return out.toString();
    }

    private static void dropLastSegment(StringBuilder out) {
        int slash = out.lastIndexOf("/");
        out.setLength(Math.max(slash, 0));
    }

    /** RFC 3986 section 5.2.2 (strict). This URI is the base and must have a scheme. */
    public Uri resolve(Uri ref) {
        Objects.requireNonNull(ref);
        if (scheme == null) throw new IllegalArgumentException("base URI has no scheme: " + this);
        if (ref.scheme != null) {
            return build(ref.scheme, ref.userInfo, ref.host, ref.port, removeDotSegments(ref.path), ref.query, ref.fragment);
        }
        if (ref.host != null) {
            return build(scheme, ref.userInfo, ref.host, ref.port, removeDotSegments(ref.path), ref.query, ref.fragment);
        }
        String p;
        String q;
        if (ref.path.isEmpty()) {
            p = path;
            q = ref.query != null ? ref.query : query;
        } else {
            if (ref.path.startsWith("/")) {
                p = removeDotSegments(ref.path);
            } else {
                p = removeDotSegments(merge(ref.path));
            }
            q = ref.query;
        }
        return build(scheme, userInfo, host, port, p, q, ref.fragment);
    }

    private String merge(String refPath) {
        if (host != null && path.isEmpty()) return "/" + refPath;
        int slash = path.lastIndexOf('/');
        return path.substring(0, slash + 1) + refPath;
    }

    // ------------------------------------------------------------ normalisation

    /** Syntax-based normalisation (RFC 3986 section 6.2.2) plus scheme-based default ports and "/" path. */
    public Uri normalize() {
        String sc = scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
        String ui = userInfo == null ? null : normPct(userInfo);
        String h = host == null ? null : lowerOutsideTriplets(normPct(host));
        int po = port;
        if (sc != null && po == defaultPort(sc)) po = -1;
        String p = removeDotSegments(normPct(path));
        if (h != null && p.isEmpty() && sc != null && HIERARCHICAL.contains(sc)) p = "/";
        return build(sc, ui, h, po, p, query == null ? null : normPct(query), fragment == null ? null : normPct(fragment));
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http", "ws" -> 80;
            case "https", "wss" -> 443;
            case "ftp" -> 21;
            default -> -2;
        };
    }

    /** Uppercases hex digits of triplets and decodes triplets that denote unreserved characters. */
    private static String normPct(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                int v = Chars.hexValue(s.charAt(i + 1)) * 16 + Chars.hexValue(s.charAt(i + 2));
                if (Chars.isUnreserved(v)) sb.append((char) v);
                else sb.append('%').append(Character.toUpperCase(s.charAt(i + 1))).append(Character.toUpperCase(s.charAt(i + 2)));
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String lowerOutsideTriplets(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                sb.append(s, i, i + 3);
                i += 2;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ relativisation

    /**
     * Returns a reference that, resolved against this base, gives {@code target}. Exact when neither path
     * contains dot segments; when the authority differs, the base has no scheme or the paths cannot be
     * related, the target itself is returned.
     */
    public Uri relativize(Uri target) {
        Objects.requireNonNull(target);
        if (scheme == null || !scheme.equals(target.scheme) || !Objects.equals(userInfo, target.userInfo)
                || !Objects.equals(host, target.host) || port != target.port) {
            return target;
        }
        if (hasDot(path) || hasDot(target.path)) return target;
        if (path.equals(target.path)) {
            if (Objects.equals(query, target.query)) return new Uri(null, null, null, -1, "", null, target.fragment);
            if (target.query != null) return new Uri(null, null, null, -1, "", target.query, target.fragment);
        }
        if (!path.startsWith("/") || !target.path.startsWith("/")) return target;
        List<String> b = segments(path), t = segments(target.path);
        List<String> bDir = b.subList(0, b.size() - 1), tDir = t.subList(0, t.size() - 1);
        int common = 0;
        while (common < bDir.size() && common < tDir.size() && bDir.get(common).equals(tDir.get(common))) common++;
        StringBuilder sb = new StringBuilder();
        for (int i = common; i < bDir.size(); i++) sb.append("../");
        for (int i = common; i < tDir.size(); i++) sb.append(tDir.get(i)).append('/');
        sb.append(t.get(t.size() - 1));
        String rel = sb.toString();
        if (rel.isEmpty() || rel.startsWith("/") || firstSegmentHasColon(rel)) rel = "./" + rel;
        return new Uri(null, null, null, -1, rel, target.query, target.fragment);
    }

    private static boolean hasDot(String path) {
        for (String s : path.split("/", -1)) if (s.equals(".") || s.equals("..")) return true;
        return false;
    }

    private static List<String> segments(String absPath) {
        return new ArrayList<>(Arrays.asList(absPath.substring(1).split("/", -1)));
    }
}
