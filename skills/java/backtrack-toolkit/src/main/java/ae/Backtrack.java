package ae;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Small backtracking toolkit: multiset permutations, N-Queens and combination sums. */
public final class Backtrack {

    private Backtrack() {}

    /** All distinct permutations of {@code values}, in lexicographic order. */
    public static List<List<Integer>> uniquePermutations(int[] values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        List<List<Integer>> out = new ArrayList<>();
        permute(sorted, new boolean[sorted.length], new ArrayList<>(), out);
        return out;
    }

    private static void permute(int[] sorted, boolean[] used, List<Integer> cur, List<List<Integer>> out) {
        if (cur.size() == sorted.length) {
            out.add(List.copyOf(cur));
            return;
        }
        for (int i = 0; i < sorted.length; i++) {
            if (used[i] || (i > 0 && sorted[i] == sorted[i - 1] && !used[i - 1])) {
                continue;
            }
            used[i] = true;
            cur.add(sorted[i]);
            permute(sorted, used, cur, out);
            cur.remove(cur.size() - 1);
            used[i] = false;
        }
    }

    /** Every placement of {@code n} non-attacking queens, as column-per-row arrays in lexicographic order. */
    public static List<int[]> nQueens(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        List<int[]> out = new ArrayList<>();
        place(n, 0, new int[n], new boolean[n], new boolean[2 * n - 1], new boolean[2 * n - 1], out);
        return out;
    }

    private static void place(int n, int row, int[] cols, boolean[] colUsed, boolean[] diag,
            boolean[] anti, List<int[]> out) {
        if (row == n) {
            out.add(cols.clone());
            return;
        }
        for (int c = 0; c < n; c++) {
            int d = row - c + n - 1;
            int a = row + c;
            if (colUsed[c] || diag[d] || anti[a]) {
                continue;
            }
            colUsed[c] = diag[d] = anti[a] = true;
            cols[row] = c;
            place(n, row + 1, cols, colUsed, diag, anti, out);
            colUsed[c] = diag[d] = anti[a] = false;
        }
    }

    /** All unique non-decreasing combinations of reusable positive candidates summing to {@code target}. */
    public static List<List<Integer>> combinationSum(int[] candidates, int target) {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null");
        }
        if (target < 0) {
            throw new IllegalArgumentException("target must be >= 0");
        }
        for (int c : candidates) {
            if (c <= 0) {
                throw new IllegalArgumentException("candidates must be positive");
            }
        }
        int[] sorted = Arrays.stream(candidates).distinct().sorted().toArray();
        List<List<Integer>> out = new ArrayList<>();
        sum(sorted, 0, target, new ArrayList<>(), out);
        return out;
    }

    private static void sum(int[] sorted, int start, long remaining, List<Integer> cur,
            List<List<Integer>> out) {
        if (remaining == 0) {
            out.add(List.copyOf(cur));
            return;
        }
        for (int i = start; i < sorted.length && sorted[i] <= remaining; i++) {
            cur.add(sorted[i]);
            sum(sorted, i, remaining - sorted[i], cur, out);
            cur.remove(cur.size() - 1);
        }
    }
}
