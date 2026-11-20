package ae.suffix;

import java.util.Objects;

/** Burrows-Wheeler transform with an explicit {@code '\0'} sentinel. */
public final class Bwt {
    /** Sentinel character, smaller than every other character. */
    public static final char SENTINEL = '\0';

    private Bwt() {}

    /** Returns the BWT (length n+1) of {@code s}; {@code s} must not contain the sentinel. */
    public static String transform(String s) {
        Objects.requireNonNull(s, "s");
        if (s.indexOf(SENTINEL) >= 0) throw new IllegalArgumentException("input contains NUL");
        String t = s + SENTINEL;
        int[] sa = SuffixArray.build(t.chars().toArray());
        char[] out = new char[t.length()];
        for (int i = 0; i < out.length; i++) {
            int prev = sa[i] - 1;
            if (prev < 0) prev = t.length() - 1;
            out[i] = t.charAt(prev);
        }
        return new String(out);
    }

    /** Inverts {@link #transform}; throws IllegalArgumentException if {@code bwt} is not a valid BWT. */
    public static String inverse(String bwt) {
        Objects.requireNonNull(bwt, "bwt");
        int m = bwt.length();
        int sentinels = 0;
        for (int i = 0; i < m; i++) if (bwt.charAt(i) == SENTINEL) sentinels++;
        if (sentinels != 1) throw new IllegalArgumentException("need exactly one sentinel");
        // lf[i] = C[c] + (occurrences of c before row i), via a stable sort of the characters
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Character.compare(bwt.charAt(x), bwt.charAt(y)));
        int[] lf = new int[m]; // sorted position j holds the row order[j]; so lf[order[j]] = j
        for (int j = 0; j < m; j++) lf[order[j]] = j;
        char[] out = new char[m - 1];
        int row = 0; // row 0 is the suffix "\0", whose BWT char is the last char of s
        for (int k = m - 2; k >= 0; k--) {
            out[k] = bwt.charAt(row);
            row = lf[row];
        }
        String result = new String(out);
        if (!transform(result).equals(bwt)) throw new IllegalArgumentException("not a valid BWT");
        return result;
    }
}
