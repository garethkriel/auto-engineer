package bench.diff;

import java.util.ArrayList;
import java.util.List;

/** Minimal line diff via LCS dynamic programming. */
public final class LineDiff {
    private LineDiff() {
    }

    public static List<String> diff(List<String> a, List<String> b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("null list");
        }
        int n = a.size();
        int m = b.size();
        String[] x = a.toArray(new String[0]);
        String[] y = b.toArray(new String[0]);
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = x[i].equals(y[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>(n + m);
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (x[i].equals(y[j])) {
                out.add(" " + x[i]);
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add("-" + x[i++]);
            } else {
                out.add("+" + y[j++]);
            }
        }
        while (i < n) {
            out.add("-" + x[i++]);
        }
        while (j < m) {
            out.add("+" + y[j++]);
        }
        return out;
    }
}
