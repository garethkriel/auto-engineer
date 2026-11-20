package ae.minisql;

/** SQL LIKE: {@code %} matches any sequence, {@code _} one char; everything else is literal and case-sensitive. */
public final class LikeMatcher {
    private LikeMatcher() {}

    /** Iterative two-pointer matcher with single-star backtracking: O(text * pattern), no regex. */
    public static boolean matches(String text, String pattern) {
        int t = 0, p = 0, starP = -1, starT = 0;
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '%') {
                starP = p++;
                starT = t;
            } else if (p < pattern.length() && (pattern.charAt(p) == '_' || pattern.charAt(p) == text.charAt(t))) {
                p++;
                t++;
            } else if (starP >= 0) {
                p = starP + 1;
                t = ++starT;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '%') p++;
        return p == pattern.length();
    }
}
