package bench.diff;

import java.util.ArrayList;
import java.util.List;

/** Minimal line diff based on an LCS table. */
public final class LineDiff {
    private LineDiff() {
    }

    public static List<String> diff(List<String> a, List<String> b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("null list");
        }
        String[] x = a.toArray(new String[0]);
        String[] y = b.toArray(new String[0]);
        int n = x.length;
        int m = y.length;
        int[][] l = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                l[i][j] = x[i].equals(y[j]) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>(n + m);
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && x[i].equals(y[j])) {
                out.add(" " + x[i]);
                i++;
                j++;
            } else if (j == m || (i < n && l[i + 1][j] >= l[i][j + 1])) {
                out.add("-" + x[i]);
                i++;
            } else {
                out.add("+" + y[j]);
                j++;
            }
        }
        return out;
    }
}
