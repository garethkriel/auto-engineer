package bench.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Minimal line diff from an LCS table over suffixes. */
public final class LineDiff {
    private LineDiff() {}

    public static List<String> diff(List<String> a, List<String> b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("null list");
        }
        List<String> x = new ArrayList<>(a);
        List<String> y = new ArrayList<>(b);
        int n = x.size();
        int m = y.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = Objects.equals(x.get(i), y.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && Objects.equals(x.get(i), y.get(j))) {
                out.add(" " + x.get(i));
                i++;
                j++;
            } else if (j >= m || (i < n && lcs[i + 1][j] >= lcs[i][j + 1])) {
                out.add("-" + x.get(i));
                i++;
            } else {
                out.add("+" + y.get(j));
                j++;
            }
        }
        return out;
    }
}
