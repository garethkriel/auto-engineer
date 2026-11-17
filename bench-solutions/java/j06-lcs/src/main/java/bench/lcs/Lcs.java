package bench.lcs;

public final class Lcs {
    private Lcs() {}

    public static int length(String a, String b) {
        check(a, b);
        if (a.length() < b.length()) { String t = a; a = b; b = t; }
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    public static String sequence(String a, String b) {
        check(a, b);
        int n = a.length(), m = b.length();
        short[][] dp = new short[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.charAt(i) == b.charAt(j) ? (short) (dp[i + 1][j + 1] + 1)
                        : (short) Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        StringBuilder sb = new StringBuilder();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a.charAt(i) == b.charAt(j)) { sb.append(a.charAt(i)); i++; j++; }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i++;
            else j++;
        }
        return sb.toString();
    }

    private static void check(String a, String b) {
        if (a == null || b == null) throw new IllegalArgumentException("null");
    }
}
