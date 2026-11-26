package bench.lcs;

/** Longest common subsequence (Hirschberg: linear memory). */
public final class Lcs {
    private Lcs() {}

    public static int length(String a, String b) {
        check(a, b);
        return lastRow(a, 0, a.length(), b, 0, b.length(), false)[b.length()];
    }

    public static String sequence(String a, String b) {
        check(a, b);
        StringBuilder sb = new StringBuilder();
        build(a, 0, a.length(), b, 0, b.length(), sb);
        return sb.toString();
    }

    private static void check(String a, String b) {
        if (a == null || b == null) throw new IllegalArgumentException("null argument");
    }

    /** LCS-length row of a[al..ah) against prefixes of b[bl..bh) (or reversed when rev). */
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

    private static void build(String a, int al, int ah, String b, int bl, int bh, StringBuilder out) {
        int n = ah - al;
        if (n == 0 || bh == bl) return;
        if (n == 1) {
            char c = a.charAt(al);
            for (int j = bl; j < bh; j++) {
                if (b.charAt(j) == c) { out.append(c); return; }
            }
            return;
        }
        int mid = al + n / 2;
        int[] left = lastRow(a, al, mid, b, bl, bh, false);
        int[] right = lastRow(a, mid, ah, b, bl, bh, true);
        int m = bh - bl, best = -1, split = 0;
        for (int j = 0; j <= m; j++) {
            int v = left[j] + right[m - j];
            if (v > best) { best = v; split = j; }
        }
        build(a, al, mid, b, bl, bl + split, out);
        build(a, mid, ah, b, bl + split, bh, out);
    }
}
