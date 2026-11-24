package ae.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Shared helpers for the tests: line splitting, seeded generators and the LCS oracle. */
final class TestSupport {
    private TestSupport() {}

    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (!text.isEmpty()) {
            for (String s : text.split(" ")) {
                out.add(s);
            }
        }
        return out;
    }

    /** Random lines over a small alphabet so repeated lines (the hard case for diffs) are common. */
    static List<String> randomLines(Random r, int maxLen, int alphabet) {
        int n = r.nextInt(maxLen + 1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("l" + r.nextInt(alphabet));
        }
        return out;
    }

    /** Random edit of a copy of {@code src}: a few inserts, deletes and replacements. */
    static List<String> mutate(Random r, List<String> src, int alphabet) {
        List<String> out = new ArrayList<>(src);
        int ops = r.nextInt(5);
        for (int i = 0; i < ops; i++) {
            int kind = r.nextInt(3);
            if (kind == 0 || out.isEmpty()) {
                out.add(r.nextInt(out.size() + 1), "l" + r.nextInt(alphabet));
            } else if (kind == 1) {
                out.remove(r.nextInt(out.size()));
            } else {
                out.set(r.nextInt(out.size()), "l" + r.nextInt(alphabet));
            }
        }
        return out;
    }

    /** Independent O(nm) dynamic-programming LCS length. */
    static int lcs(List<String> a, List<String> b) {
        int[][] t = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                t[i][j] = a.get(i).equals(b.get(j))
                        ? t[i + 1][j + 1] + 1
                        : Math.max(t[i + 1][j], t[i][j + 1]);
            }
        }
        return t[0][0];
    }
}
