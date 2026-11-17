package bench.lcs;

/** Longest common subsequence. */
public final class Lcs {
    private Lcs() {}

    public static int length(String a, String b) {
        check(a, b);
        int m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 1; i <= a.length(); i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                cur[j] = ca == b.charAt(j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    public static String sequence(String a, String b) {
        check(a, b);
        int n = a.length();
        int m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        byte[][] dir = new byte[n + 1][m + 1]; // 1 diagonal, 2 up, 3 left
        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                if (ca == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    dir[i][j] = 1;
                } else if (prev[j] >= cur[j - 1]) {
                    cur[j] = prev[j];
                    dir[i][j] = 2;
                } else {
                    cur[j] = cur[j - 1];
                    dir[i][j] = 3;
                }
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        StringBuilder sb = new StringBuilder();
        int i = n;
        int j = m;
        while (i > 0 && j > 0) {
            byte d = dir[i][j];
            if (d == 1) {
                sb.append(a.charAt(i - 1));
                i--;
                j--;
            } else if (d == 2) {
                i--;
            } else {
                j--;
            }
        }
        return sb.reverse().toString();
    }

    private static void check(String a, String b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("null argument");
        }
    }
}
