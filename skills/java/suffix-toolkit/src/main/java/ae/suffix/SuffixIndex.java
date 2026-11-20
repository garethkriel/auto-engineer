package ae.suffix;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Searchable index over one string: suffix array + LCP array. */
public final class SuffixIndex {
    private final String text;
    private final int[] sa;
    private final int[] lcp;

    public SuffixIndex(String text) {
        this.text = Objects.requireNonNull(text, "text");
        int[] t = text.chars().toArray();
        this.sa = SuffixArray.build(t);
        this.lcp = SuffixArray.lcp(t, sa);
    }

    /** Compares {@code p} with the first p.length() chars of the suffix at {@code start}. */
    private int compare(String p, int start) {
        int len = Math.min(p.length(), text.length() - start);
        for (int i = 0; i < len; i++) {
            int d = p.charAt(i) - text.charAt(start + i);
            if (d != 0) return d;
        }
        if (len < p.length()) return 1; // suffix ran out: pattern is larger
        return 0;
    }

    /** Half-open range [lo, hi) of suffix-array rows whose suffix starts with p. */
    private int[] range(String p) {
        if (p.isEmpty()) throw new IllegalArgumentException("empty pattern");
        int lo = 0;
        int hi = sa.length;
        while (lo < hi) { // first row with suffix >= p (prefix-wise)
            int mid = (lo + hi) >>> 1;
            if (compare(p, sa[mid]) > 0) lo = mid + 1;
            else hi = mid;
        }
        int first = lo;
        hi = sa.length;
        while (lo < hi) { // first row with suffix strictly greater than p-prefix
            int mid = (lo + hi) >>> 1;
            if (compare(p, sa[mid]) >= 0) lo = mid + 1;
            else hi = mid;
        }
        return new int[] {first, lo};
    }

    public int count(String pattern) {
        int[] r = range(Objects.requireNonNull(pattern, "pattern"));
        return r[1] - r[0];
    }

    public boolean contains(String pattern) {
        return count(pattern) > 0;
    }

    /** Start positions of every (possibly overlapping) occurrence, ascending. */
    public List<Integer> occurrences(String pattern) {
        int[] r = range(Objects.requireNonNull(pattern, "pattern"));
        List<Integer> out = new ArrayList<>(r[1] - r[0]);
        for (int i = r[0]; i < r[1]; i++) out.add(sa[i]);
        out.sort(null);
        return out;
    }

    /** Longest substring occurring at least twice (overlaps allowed); lexicographically smallest on ties. */
    public String longestRepeatedSubstring() {
        int best = 0;
        int row = -1;
        for (int i = 1; i < sa.length; i++) {
            if (lcp[i] > best) {
                best = lcp[i];
                row = i;
            }
        }
        if (row < 0) return "";
        return text.substring(sa[row], sa[row] + best);
    }

    /** Number of distinct non-empty substrings. */
    public long distinctSubstrings() {
        long n = sa.length;
        long total = n * (n + 1) / 2;
        for (int h : lcp) total -= h;
        return total;
    }

    /** The k-th (1-based) distinct substring in lexicographic order. */
    public String kthSubstring(long k) {
        if (k < 1 || k > distinctSubstrings()) {
            throw new IllegalArgumentException("k out of range: " + k);
        }
        long remaining = k;
        for (int i = 0; i < sa.length; i++) {
            long fresh = (long) (sa.length - sa[i]) - lcp[i]; // new prefixes contributed by this row
            if (remaining <= fresh) {
                return text.substring(sa[i], sa[i] + lcp[i] + (int) remaining);
            }
            remaining -= fresh;
        }
        throw new IllegalStateException("unreachable");
    }

    /** Longest substring common to both strings; lexicographically smallest on ties. */
    public static String longestCommonSubstring(String a, String b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        int[] t = new int[a.length() + 1 + b.length()];
        for (int i = 0; i < a.length(); i++) t[i] = a.charAt(i);
        t[a.length()] = -1; // unique separator: chars are never negative
        for (int i = 0; i < b.length(); i++) t[a.length() + 1 + i] = b.charAt(i);
        int[] sa = SuffixArray.build(t);
        int[] lcp = SuffixArray.lcp(t, sa);
        int best = 0;
        int start = 0;
        for (int i = 1; i < sa.length; i++) {
            boolean prevInA = sa[i - 1] < a.length();
            boolean curInA = sa[i] < a.length();
            boolean separator = sa[i - 1] == a.length() || sa[i] == a.length();
            if (!separator && prevInA != curInA && lcp[i] > best) {
                best = lcp[i];
                start = sa[i];
            }
        }
        if (best == 0) return "";
        String joined = a + '￿' + b; // only used to slice; start lies inside a or b
        if (start < a.length()) return a.substring(start, start + best);
        return b.substring(start - a.length() - 1, start - a.length() - 1 + best);
    }
}
