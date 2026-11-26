package bench.intervals;

import java.util.ArrayList;
import java.util.List;

public final class Intervals {
    private Intervals() {}

    public static List<int[]> merge(List<int[]> intervals) {
        if (intervals == null) throw new IllegalArgumentException("null list");
        List<int[]> copy = new ArrayList<>(intervals.size());
        for (int[] iv : intervals) {
            if (iv == null) throw new IllegalArgumentException("null interval");
            if (iv.length != 2) throw new IllegalArgumentException("bad length");
            if (iv[0] > iv[1]) throw new IllegalArgumentException("start > end");
            copy.add(new int[]{iv[0], iv[1]});
        }
        copy.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] iv : copy) {
            if (!out.isEmpty() && (long) iv[0] <= (long) out.get(out.size() - 1)[1]) {
                int[] last = out.get(out.size() - 1);
                if (iv[1] > last[1]) last[1] = iv[1];
            } else {
                out.add(iv);
            }
        }
        return out;
    }
}
