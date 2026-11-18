package bench.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        List<String> out = new ArrayList<>(n + m);
        int pre = 0;
        while (pre < n && pre < m && Objects.equals(x[pre], y[pre])) {
            out.add(" " + x[pre]);
            pre++;
        }
        // l[i][j] = LCS of x[i..], y[j..], restricted to the part after the common prefix
        int rn = n - pre;
        int rm = m - pre;
        int[][] l = new int[rn + 1][rm + 1];
        for (int i = rn - 1; i >= 0; i--) {
            for (int j = rm - 1; j >= 0; j--) {
                if (Objects.equals(x[pre + i], y[pre + j])) {
                    l[i][j] = l[i + 1][j + 1] + 1;
                } else {
                    l[i][j] = Math.max(l[i + 1][j], l[i][j + 1]);
                }
            }
        }
        int i = 0;
        int j = 0;
        while (i < rn || j < rm) {
            if (i < rn && j < rm && Objects.equals(x[pre + i], y[pre + j])) {
                out.add(" " + x[pre + i]);
                i++;
                j++;
            } else if (i < rn && (j >= rm || l[i + 1][j] >= l[i][j + 1])) {
                out.add("-" + x[pre + i]);
                i++;
            } else {
                out.add("+" + y[pre + j]);
                j++;
            }
        }
        return out;
    }
}
