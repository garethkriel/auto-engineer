package bench.lcs;

public final class Lcs {
    private Lcs() {}

    private static short[][] table(String a, String b) {
        int n = a.length(), m = b.length();
        short[][] t = new short[n + 1][m + 1];
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++)
                t[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? (short) (t[i - 1][j - 1] + 1)
                        : (short) Math.max(t[i - 1][j], t[i][j - 1]);
        return t;
    }

    private static void check(String a, String b) {
        if (a == null || b == null) throw new IllegalArgumentException("null");
    }

    public static int length(String a, String b) {
        check(a, b);
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++)
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    public static String sequence(String a, String b) {
        check(a, b);
        // short overflows beyond 32767; strings of "a few thousand" are fine, else fall back to int
        if (Math.min(a.length(), b.length()) > 32000) return sequenceInt(a, b);
        short[][] t = table(a, b);
        StringBuilder sb = new StringBuilder();
        int i = a.length(), j = b.length();
        while (i > 0 && j > 0) {
            if (a.charAt(i - 1) == b.charAt(j - 1)) { sb.append(a.charAt(i - 1)); i--; j--; }
            else if (t[i - 1][j] >= t[i][j - 1]) i--;
            else j--;
        }
        return sb.reverse().toString();
    }

    private static String sequenceInt(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] t = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++)
                t[i][j] = a.charAt(i - 1) == b.charAt(j - 1) ? t[i - 1][j - 1] + 1 : Math.max(t[i - 1][j], t[i][j - 1]);
        StringBuilder sb = new StringBuilder();
        int i = n, j = m;
        while (i > 0 && j > 0) {
            if (a.charAt(i - 1) == b.charAt(j - 1)) { sb.append(a.charAt(i - 1)); i--; j--; }
            else if (t[i - 1][j] >= t[i][j - 1]) i--;
            else j--;
        }
        return sb.reverse().toString();
    }
}
