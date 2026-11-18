package ae.notify;

/** Shared text helpers (the legacy code copy-pasted these). */
final class Text {
    private Text() {}

    /** Cuts to {@code max} chars by keeping {@code max-3} and appending "..." when longer than max. */
    static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    static String orDefault(String s, String fallback) {
        return s.isEmpty() ? fallback : s;
    }
}
