package ae.flow;

import java.util.Objects;

/** Hungarian algorithm (potentials, O(n^2 m)) for minimum-cost assignment of rows to distinct columns. */
public final class Assignment {

    /** Largest accepted absolute matrix entry. */
    public static final long MAX_ABS_COST = 1_000_000_000_000L;

    /** Chosen column of each row and the total cost. */
    public record Result(int[] columnOfRow, long totalCost) {}

    private Assignment() {}

    /** Requires a rectangular matrix with rows &lt;= columns. */
    public static Result solve(long[][] cost) {
        Objects.requireNonNull(cost, "cost");
        int n = cost.length;
        int m = n == 0 ? 0 : cost[0].length;
        if (n > m) {
            throw new IllegalArgumentException("more rows (" + n + ") than columns (" + m + ")");
        }
        for (long[] row : cost) {
            if (row.length != m) {
                throw new IllegalArgumentException("ragged matrix");
            }
            for (long c : row) {
                if (Math.abs(c) > MAX_ABS_COST) {
                    throw new IllegalArgumentException("cost out of range: " + c);
                }
            }
        }
        long[] u = new long[n + 1];
        long[] v = new long[m + 1];
        int[] p = new int[m + 1]; // p[j] = row (1-based) matched to column j (1-based)
        int[] way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            long[] minv = new long[m + 1];
            boolean[] used = new boolean[m + 1];
            java.util.Arrays.fill(minv, Long.MAX_VALUE);
            do {
                used[j0] = true;
                int i0 = p[j0];
                long delta = Long.MAX_VALUE;
                int j1 = 0;
                for (int j = 1; j <= m; j++) {
                    if (!used[j]) {
                        long cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] columnOfRow = new int[n];
        long total = 0;
        for (int j = 1; j <= m; j++) {
            if (p[j] != 0) {
                columnOfRow[p[j] - 1] = j - 1;
                total += cost[p[j] - 1][j - 1];
            }
        }
        return new Result(columnOfRow, total);
    }
}
