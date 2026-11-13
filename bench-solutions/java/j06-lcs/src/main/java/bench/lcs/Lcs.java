package bench.lcs;

/** Longest common subsequence via DP table. */
public final class Lcs {
    private Lcs() {}

    private static int[][] table(String a, String b) {
        if (a == null || b == null) throw new IllegalArgumentException("null");
        int[][] t = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                t[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? t[i - 1][j - 1] + 1 : Math.max(t[i - 1][j], t[i][j - 1]);
            }
        }
        return t;
    }

    public static int length(String a, String b) {
        int[][] t = table(a, b);
        return t[a.length()][b.length()];
    }

    public static String sequence(String a, String b) {
        int[][] t = table(a, b);
        StringBuilder sb = new StringBuilder();
        int i = a.length(), j = b.length();
        while (i > 0 && j > 0) {
            if (a.charAt(i - 1) == b.charAt(j - 1)) { sb.append(a.charAt(i - 1)); i--; j--; }
            else if (t[i - 1][j] >= t[i][j - 1]) i--;
            else j--;
        }
        return sb.reverse().toString();
    }
}
