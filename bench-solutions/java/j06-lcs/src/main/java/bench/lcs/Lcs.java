package bench.lcs;

/** Longest common subsequence (linear space, Hirschberg for reconstruction). */
public final class Lcs {
    private Lcs() {}

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

    /** LCS lengths of a[as..ae) against every prefix of b[bs..be) (or suffix if reversed). */
    private static int[] lastRow(String a, int as, int ae, String b, int bs, int be, boolean rev) {
        int m = be - bs;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 0; i < ae - as; i++) {
            char ca = rev ? a.charAt(ae - 1 - i) : a.charAt(as + i);
            for (int j = 1; j <= m; j++) {
                char cb = rev ? b.charAt(be - j) : b.charAt(bs + j - 1);
                cur[j] = ca == cb ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev;
    }

    private static void hirschberg(String a, int as, int ae, String b, int bs, int be, StringBuilder out) {
        int n = ae - as;
        int m = be - bs;
        if (n == 0 || m == 0) {
            return;
        }
        if (n == 1) {
            char c = a.charAt(as);
            if (b.indexOf(c, bs) >= 0 && b.indexOf(c, bs) < be) {
                out.append(c);
            }
            return;
        }
        int mid = as + n / 2;
        int[] l1 = lastRow(a, as, mid, b, bs, be, false);
        int[] l2 = lastRow(a, mid, ae, b, bs, be, true);
        int best = -1;
        int split = 0;
        for (int j = 0; j <= m; j++) {
            int v = l1[j] + l2[m - j];
            if (v > best) {
                best = v;
                split = j;
            }
        }
        hirschberg(a, as, mid, b, bs, bs + split, out);
        hirschberg(a, mid, ae, b, bs + split, be, out);
    }
}
