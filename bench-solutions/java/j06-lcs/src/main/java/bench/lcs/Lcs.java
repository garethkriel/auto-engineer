package bench.lcs;

/** Longest common subsequence. */
public final class Lcs {
    private Lcs() { }

    private static short[][] table(String a, String b) {
        short[][] t = new short[a.length() + 1][b.length() + 1];
        for (int i = a.length() - 1; i >= 0; i--) {
            for (int j = b.length() - 1; j >= 0; j--) {
                t[i][j] = a.charAt(i) == b.charAt(j)
                        ? (short) (t[i + 1][j + 1] + 1)
                        : (short) Math.max(t[i + 1][j], t[i][j + 1]);
            }
        }
        return t;
    }

    private static void check(String a, String b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("null argument");
        }
    }

    public static int length(String a, String b) {
        check(a, b);
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = a.length() - 1; i >= 0; i--) {
            for (int j = b.length() - 1; j >= 0; j--) {
                cur[j] = a.charAt(i) == b.charAt(j) ? prev[j + 1] + 1 : Math.max(prev[j], cur[j + 1]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[0];
    }

    public static String sequence(String a, String b) {
        check(a, b);
        if (Math.min(a.length(), b.length()) > Short.MAX_VALUE) {
            // lengths beyond short range: fall back to int table
            return sequenceInt(a, b);
        }
        short[][] t = table(a, b);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) {
                sb.append(a.charAt(i));
                i++;
                j++;
            } else if (t[i + 1][j] >= t[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return sb.toString();
    }

    private static String sequenceInt(String a, String b) {
        int[][] t = new int[a.length() + 1][b.length() + 1];
        for (int i = a.length() - 1; i >= 0; i--) {
            for (int j = b.length() - 1; j >= 0; j--) {
                t[i][j] = a.charAt(i) == b.charAt(j) ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1]);
            }
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) {
                sb.append(a.charAt(i));
                i++;
                j++;
            } else if (t[i + 1][j] >= t[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return sb.toString();
    }
}
