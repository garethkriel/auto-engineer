package bench.lcs;

/** Longest common subsequence (over UTF-16 chars). */
public final class Lcs {
    private Lcs() {
    }

    public static int length(String a, String b) {
        check(a, b);
        return lastRow(a, 0, a.length(), b, 0, b.length(), false)[b.length()];
    }

    public static String sequence(String a, String b) {
        check(a, b);
        StringBuilder sb = new StringBuilder();
        hirschberg(a, 0, a.length(), b, 0, b.length(), sb);
        return sb.toString();
    }

    private static void check(String a, String b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("null argument");
        }
    }

    /** LCS-length row for a[ai,aj) against prefixes of b[bi,bj); optionally reversed. */
    private static int[] lastRow(String a, int ai, int aj, String b, int bi, int bj, boolean reverse) {
        int m = bj - bi;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int x = 0; x < aj - ai; x++) {
            char ca = reverse ? a.charAt(aj - 1 - x) : a.charAt(ai + x);
            for (int y = 1; y <= m; y++) {
                char cb = reverse ? b.charAt(bj - y) : b.charAt(bi + y - 1);
                cur[y] = ca == cb ? prev[y - 1] + 1 : Math.max(prev[y], cur[y - 1]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev;
    }

    private static void hirschberg(String a, int ai, int aj, String b, int bi, int bj, StringBuilder out) {
        int n = aj - ai;
        int m = bj - bi;
        if (n == 0 || m == 0) {
            return;
        }
        if (n == 1) {
            char c = a.charAt(ai);
            for (int y = bi; y < bj; y++) {
                if (b.charAt(y) == c) {
                    out.append(c);
                    return;
                }
            }
            return;
        }
        int mid = ai + n / 2;
        int[] left = lastRow(a, ai, mid, b, bi, bj, false);
        int[] right = lastRow(a, mid, aj, b, bi, bj, true);
        int best = -1;
        int split = 0;
        for (int k = 0; k <= m; k++) {
            int v = left[k] + right[m - k];
            if (v > best) {
                best = v;
                split = k;
            }
        }
        hirschberg(a, ai, mid, b, bi, bi + split, out);
        hirschberg(a, mid, aj, b, bi + split, bj, out);
    }
}
