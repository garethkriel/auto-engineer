package bench.intervals;

import java.util.ArrayList;
import java.util.List;

public final class Intervals {
    private Intervals() {}

    public static List<int[]> merge(List<int[]> intervals) {
        if (intervals == null) throw new IllegalArgumentException("null list");
        List<int[]> copy = new ArrayList<>();
        for (int[] a : intervals) {
            if (a == null || a.length != 2 || a[0] > a[1]) throw new IllegalArgumentException("bad interval");
            copy.add(new int[] {a[0], a[1]});
        }
        copy.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] a : copy) {
            if (!out.isEmpty()) {
                int[] last = out.get(out.size() - 1);
                if (a[0] <= last[1]) {
                    last[1] = Math.max(last[1], a[1]);
                    continue;
                }
            }
            out.add(a);
        }
        return out;
    }
}
