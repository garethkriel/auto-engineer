package bench.diff;

import java.util.ArrayList;
import java.util.List;

public final class LineDiff {
    private LineDiff() {}

    public static List<String> diff(List<String> a, List<String> b) {
        if (a == null || b == null) throw new IllegalArgumentException("null");
        int n = a.size(), m = b.size();
        // suffix LCS table: l[i][j] = LCS(a[i..], b[j..])
        int[][] l = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                l[i][j] = a.get(i).equals(b.get(j)) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(i).equals(b.get(j))) {
                out.add(" " + a.get(i)); i++; j++;
            } else if (i < n && (j == m || l[i + 1][j] >= l[i][j + 1])) {
                out.add("-" + a.get(i)); i++;
            } else {
                out.add("+" + b.get(j)); j++;
            }
        }
        return out;
    }
}
