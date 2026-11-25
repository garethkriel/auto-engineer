package bench.lcs;

/** Longest common subsequence using linear-space Hirschberg reconstruction. */
public final class Lcs {
    private Lcs() {}

    public static int length(String a, String b) {
        check(a, b);
        if (a.length() < b.length()) {
            String t = a; a = b; b = t;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            char c = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = c == b.charAt(j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
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

    private static void hirschberg(String a, int a0, int a1, String b, int b0, int b1, StringBuilder out) {
        int n = a1 - a0;
        int m = b1 - b0;
        if (n == 0 || m == 0) {
            return;
        }
        if (n == 1) {
            char c = a.charAt(a0);
            for (int j = b0; j < b1; j++) {
                if (b.charAt(j) == c) {
                    out.append(c);
                    return;
                }
            }
            return;
        }
        int mid = a0 + n / 2;
        int[] left = forward(a, a0, mid, b, b0, b1);
        int[] right = backward(a, mid, a1, b, b0, b1);
        int best = -1;
        int split = 0;
        for (int k = 0; k <= m; k++) {
            int v = left[k] + right[k];
            if (v > best) {
                best = v;
                split = k;
            }
        }
        hirschberg(a, a0, mid, b, b0, b0 + split, out);
        hirschberg(a, mid, a1, b, b0 + split, b1, out);
    }

    /** row[k] = LCS(a[a0..a1), b[b0..b0+k)). */
    private static int[] forward(String a, int a0, int a1, String b, int b0, int b1) {
        int m = b1 - b0;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = a0; i < a1; i++) {
            char c = a.charAt(i);
            cur[0] = 0;
            for (int j = 1; j <= m; j++) {
                cur[j] = c == b.charAt(b0 + j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev;
    }

    /** row[k] = LCS(a[a0..a1), b[b0+k..b1)). */
    private static int[] backward(String a, int a0, int a1, String b, int b0, int b1) {
        int m = b1 - b0;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = a1 - 1; i >= a0; i--) {
            char c = a.charAt(i);
            cur[m] = 0;
            for (int j = m - 1; j >= 0; j--) {
                cur[j] = c == b.charAt(b0 + j) ? prev[j + 1] + 1 : Math.max(prev[j], cur[j + 1]);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev;
    }
}
