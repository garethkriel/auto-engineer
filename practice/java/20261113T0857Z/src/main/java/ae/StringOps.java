package ae;

import java.util.Locale;
import java.util.Objects;

/** Small String utilities: run-length coding, palindromes and word capitalisation. */
public final class StringOps {
    private StringOps() {}

    /** Run-length encodes {@code s}; input must not contain digits. */
    public static String runLengthEncode(String s) {
        Objects.requireNonNull(s, "s");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                throw new IllegalArgumentException("digits are not allowed: index " + i);
            }
            int j = i;
            while (j < s.length() && s.charAt(j) == c) {
                j++;
            }
            sb.append(j - i).append(c);
            i = j;
        }
        return sb.toString();
    }

    /** Inverse of {@link #runLengthEncode}; each run is a positive decimal count then one char. */
    public static String runLengthDecode(String s) {
        Objects.requireNonNull(s, "s");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = i;
            long count = 0;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                count = count * 10 + (s.charAt(i) - '0');
                if (count > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("count too large at index " + start);
                }
                i++;
            }
            if (i == start) {
                throw new IllegalArgumentException("missing count at index " + i);
            }
            if (i >= s.length()) {
                throw new IllegalArgumentException("count without character at end");
            }
            if (count == 0) {
                throw new IllegalArgumentException("zero count at index " + start);
            }
            if (sb.length() + count > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("decoded length too large");
            }
            sb.append(String.valueOf(s.charAt(i)).repeat((int) count));
            i++;
        }
        return sb.toString();
    }

    /** True if letters/digits of {@code s}, ignoring case, read the same both ways. */
    public static boolean isPalindrome(String s) {
        Objects.requireNonNull(s, "s");
        int l = 0;
        int r = s.length() - 1;
        while (l < r) {
            if (!Character.isLetterOrDigit(s.charAt(l))) {
                l++;
            } else if (!Character.isLetterOrDigit(s.charAt(r))) {
                r--;
            } else {
                if (Character.toLowerCase(s.charAt(l)) != Character.toLowerCase(s.charAt(r))) {
                    return false;
                }
                l++;
                r--;
            }
        }
        return true;
    }

    /** Capitalises each whitespace-separated word, keeping the original whitespace. */
    public static String capitalizeWords(String s) {
        Objects.requireNonNull(s, "s");
        StringBuilder sb = new StringBuilder(s.length());
        boolean start = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                sb.append(c);
                start = true;
            } else {
                String t = String.valueOf(c);
                sb.append(start ? t.toUpperCase(Locale.ROOT) : t.toLowerCase(Locale.ROOT));
                start = false;
            }
        }
        return sb.toString();
    }
}
