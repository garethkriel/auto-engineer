package ae.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Line-based minimal diff using Myers' O(ND) greedy algorithm.
 *
 * <p>The edit script is a minimal sequence of single-line {@code DELETE}/{@code INSERT}
 * operations (plus {@code KEEP} for common lines). Among minimal scripts the one returned
 * prefers deletions before insertions inside a change run, which is deterministic.
 * Memory is O(D^2) because only the live diagonal window of each round is retained.
 */
public final class Diff {
    private Diff() {}

    /** Kind of a single edit. */
    public enum Op { KEEP, DELETE, INSERT }

    /** One line of the script. */
    public record Edit(Op op, String line) {
        public Edit {
            Objects.requireNonNull(op);
            Objects.requireNonNull(line);
        }
    }

    /** A maximal run of changes: old lines {@code [aStart, aEnd)} are replaced by {@code replacement}. */
    public record Region(int aStart, int aEnd, List<String> replacement) {
        public Region {
            replacement = List.copyOf(replacement);
        }
    }

    /** Minimal edit script turning {@code a} into {@code b}. */
    public static List<Edit> diff(List<String> a, List<String> b) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        int n = a.size();
        int m = b.size();
        int max = n + m;
        int off = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        int finalD = -1;
        search:
        for (int d = 0; d <= max; d++) {
            // snapshot of diagonals [-d-1, d+1] as they stood after round d-1
            int[] snap = new int[2 * d + 3];
            System.arraycopy(v, off - d - 1, snap, 0, snap.length);
            trace.add(snap);
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) {
                    x = v[off + k + 1];
                } else {
                    x = v[off + k - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) {
                    finalD = d;
                    break search;
                }
            }
        }
        return backtrack(a, b, trace, finalD);
    }

    private static List<Edit> backtrack(List<String> a, List<String> b, List<int[]> trace, int finalD) {
        List<Edit> out = new ArrayList<>();
        int x = a.size();
        int y = b.size();
        for (int d = finalD; d > 0; d--) {
            int[] s = trace.get(d);
            int o = d + 1;
            int k = x - y;
            int prevK = (k == -d || (k != d && s[o + k - 1] < s[o + k + 1])) ? k + 1 : k - 1;
            int prevX = s[o + prevK];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                out.add(new Edit(Op.KEEP, a.get(x - 1)));
                x--;
                y--;
            }
            if (x == prevX) {
                out.add(new Edit(Op.INSERT, b.get(y - 1)));
                y--;
            } else {
                out.add(new Edit(Op.DELETE, a.get(x - 1)));
                x--;
            }
        }
        while (x > 0 && y > 0) {
            out.add(new Edit(Op.KEEP, a.get(x - 1)));
            x--;
            y--;
        }
        Collections.reverse(out);
        return out;
    }

    /** Number of inserted plus deleted lines in a minimal script. */
    public static int distance(List<String> a, List<String> b) {
        int d = 0;
        for (Edit e : diff(a, b)) {
            if (e.op() != Op.KEEP) {
                d++;
            }
        }
        return d;
    }

    /** Maximal change runs of the minimal script, ordered and separated by at least one kept line. */
    public static List<Region> regions(List<String> a, List<String> b) {
        List<Region> out = new ArrayList<>();
        int aPos = 0;
        int start = -1;
        List<String> repl = new ArrayList<>();
        for (Edit e : diff(a, b)) {
            if (e.op() == Op.KEEP) {
                if (start >= 0) {
                    out.add(new Region(start, aPos, repl));
                    start = -1;
                    repl = new ArrayList<>();
                }
                aPos++;
            } else {
                if (start < 0) {
                    start = aPos;
                }
                if (e.op() == Op.DELETE) {
                    aPos++;
                } else {
                    repl.add(e.line());
                }
            }
        }
        if (start >= 0) {
            out.add(new Region(start, aPos, repl));
        }
        return out;
    }
}
