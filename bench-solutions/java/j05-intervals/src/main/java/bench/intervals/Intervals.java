package bench.intervals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Interval merging. */
public final class Intervals {
    private Intervals() { }

    public static List<int[]> merge(List<int[]> intervals) {
        if (intervals == null) {
            throw new IllegalArgumentException("null list");
        }
        List<int[]> copy = new ArrayList<>();
        for (int[] iv : intervals) {
            if (iv == null || iv.length != 2 || iv[0] > iv[1]) {
                throw new IllegalArgumentException("bad interval");
            }
            copy.add(new int[] {iv[0], iv[1]});
        }
        copy.sort(Comparator.comparingInt((int[] a) -> a[0]).thenComparingInt(a -> a[1]));
        List<int[]> out = new ArrayList<>();
        for (int[] iv : copy) {
            if (!out.isEmpty() && (long) iv[0] <= (long) out.get(out.size() - 1)[1]) {
                int[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], iv[1]);
            } else {
                out.add(iv);
            }
        }
        return out;
    }
}
