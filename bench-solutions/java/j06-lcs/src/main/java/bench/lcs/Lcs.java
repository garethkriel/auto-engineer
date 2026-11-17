package bench.lcs;

/** Longest common subsequence via dynamic programming. */
public final class Lcs {
    private Lcs() { }

    public static int length(String a, String b) {
        check(a, b);
        int m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= m; j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
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
        // dir: 1 = diagonal (match), 2 = up (i-1), 3 = left (j-1)
        byte[][] dir = new byte[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
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
            if (dir[i][j] == 1) {
                sb.append(a.charAt(i - 1));
                i--;
                j--;
            } else if (dir[i][j] == 2) {
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
