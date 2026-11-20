package bench.lcs;

/** Longest common subsequence (Hirschberg, linear memory). */
public final class Lcs {
    private Lcs() {}

    public static int length(String a, String b) {
        check(a, b);
        return lastRow(a, 0, a.length(), b, 0, b.length())[b.length()];
    }

    public static String sequence(String a, String b) {
        check(a, b);
        StringBuilder sb = new StringBuilder();
        build(a, 0, a.length(), b, 0, b.length(), sb);
        return sb.toString();
    }

    private static void check(String a, String b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("null argument");
        }
    }

    /** LCS lengths of a[a0,a1) against every prefix of b[b0,b1). */
    private static int[] lastRow(String a, int a0, int a1, String b, int b0, int b1) {
        int m = b1 - b0;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = a0; i < a1; i++) {
            char c = a.charAt(i);
            for (int j = 1; j <= m; j++) {
                cur[j] = c == b.charAt(b0 + j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev;
    }

    /** LCS lengths of reversed a[a0,a1) against every suffix of b[b0,b1): result[j] for b suffix starting at b0+j. */
    private static int[] lastRowRev(String a, int a0, int a1, String b, int b0, int b1) {
        int m = b1 - b0;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = a1 - 1; i >= a0; i--) {
            char c = a.charAt(i);
            for (int j = m - 1; j >= 0; j--) {
                cur[j] = c == b.charAt(b0 + j) ? prev[j + 1] + 1 : Math.max(prev[j], cur[j + 1]);
            }
            cur[m] = 0;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev;
    }

    private static void build(String a, int a0, int a1, String b, int b0, int b1, StringBuilder out) {
        if (a0 >= a1 || b0 >= b1) {
            return;
        }
        if (a1 - a0 == 1) {
            char c = a.charAt(a0);
            if (b.indexOf(c, b0) >= 0 && b.indexOf(c, b0) < b1) {
                out.append(c);
            }
            return;
        }
        int mid = (a0 + a1) >>> 1;
        int[] left = lastRow(a, a0, mid, b, b0, b1);
        int[] right = lastRowRev(a, mid, a1, b, b0, b1);
        int best = -1;
        int split = 0;
        for (int j = 0; j <= b1 - b0; j++) {
            int v = left[j] + right[j];
            if (v > best) {
                best = v;
                split = j;
            }
        }
        build(a, a0, mid, b, b0, b0 + split, out);
        build(a, mid, a1, b, b0 + split, b1, out);
    }
}
