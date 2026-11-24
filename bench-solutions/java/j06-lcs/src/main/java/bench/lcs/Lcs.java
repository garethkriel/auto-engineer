package bench.lcs;

/** Longest common subsequence using linear-space DP (Hirschberg for the sequence). */
public final class Lcs {
    private Lcs() { }

    public static int length(String a, String b) {
        check(a, b);
        return lastRow(a, 0, a.length(), b, 0, b.length(), false)[b.length()];
    }

    public static String sequence(String a, String b) {
        check(a, b);
        StringBuilder sb = new StringBuilder();
        rec(a, 0, a.length(), b, 0, b.length(), sb);
        return sb.toString();
    }

    private static void check(String a, String b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("null argument");
        }
    }

    /** Row of LCS lengths of a[al..ah) vs every prefix of b[bl..bh) (or suffix if reversed). */
    private static int[] lastRow(String a, int al, int ah, String b, int bl, int bh, boolean rev) {
        int m = bh - bl;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 0; i < ah - al; i++) {
            char ca = rev ? a.charAt(ah - 1 - i) : a.charAt(al + i);
            for (int j = 1; j <= m; j++) {
                char cb = rev ? b.charAt(bh - j) : b.charAt(bl + j - 1);
                cur[j] = ca == cb ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev;
    }

    private static void rec(String a, int al, int ah, String b, int bl, int bh, StringBuilder out) {
        int n = ah - al;
        int m = bh - bl;
        if (n == 0 || m == 0) {
            return;
        }
        if (n == 1) {
            char c = a.charAt(al);
            if (b.indexOf(c, bl) >= 0 && b.indexOf(c, bl) < bh) {
                out.append(c);
            }
            return;
        }
        int mid = al + n / 2;
        int[] l1 = lastRow(a, al, mid, b, bl, bh, false);
        int[] l2 = lastRow(a, mid, ah, b, bl, bh, true);
        int best = -1;
        int split = 0;
        for (int k = 0; k <= m; k++) {
            int v = l1[k] + l2[m - k];
            if (v > best) {
                best = v;
                split = k;
            }
        }
        rec(a, al, mid, b, bl, bl + split, out);
        rec(a, mid, ah, b, bl + split, bh, out);
    }
}
