package bench.ranges;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Set of longs stored as disjoint, non-adjacent half-open ranges. */
public final class LongRangeSet {
    private final TreeMap<Long, Long> map = new TreeMap<>();

    public LongRangeSet() {}

    public void add(long from, long to) {
        check(from, to);
        if (from == to) {
            return;
        }
        long nf = from;
        long nt = to;
        Map.Entry<Long, Long> e = map.floorEntry(from);
        if (e != null && e.getValue() >= from) {
            nf = e.getKey();
            nt = Math.max(nt, e.getValue());
            map.remove(e.getKey());
        }
        while ((e = map.ceilingEntry(from)) != null && e.getKey() <= nt) {
            nt = Math.max(nt, e.getValue());
            map.remove(e.getKey());
        }
        map.put(nf, nt);
    }

    public void remove(long from, long to) {
        check(from, to);
        if (from == to) {
            return;
        }
        Map.Entry<Long, Long> e = map.floorEntry(from);
        if (e != null && e.getKey() < from && e.getValue() > from) {
            long end = e.getValue();
            map.put(e.getKey(), from);
            if (end > to) {
                map.put(to, end);
            }
        }
        while ((e = map.ceilingEntry(from)) != null && e.getKey() < to) {
            map.remove(e.getKey());
            if (e.getValue() > to) {
                map.put(to, e.getValue());
            }
        }
    }

    public boolean contains(long x) {
        Map.Entry<Long, Long> e = map.floorEntry(x);
        return e != null && x < e.getValue();
    }

    public List<long[]> ranges() {
        List<long[]> out = new ArrayList<>(map.size());
        for (Map.Entry<Long, Long> e : map.entrySet()) {
            out.add(new long[] {e.getKey(), e.getValue()});
        }
        return out;
    }

    public long size() {
        long total = 0;
        for (Map.Entry<Long, Long> e : map.entrySet()) {
            long len = e.getValue() - e.getKey(); // negative means the true length exceeds Long.MAX_VALUE
            if (len < 0 || total > Long.MAX_VALUE - len) {
                return Long.MAX_VALUE;
            }
            total += len;
        }
        return total;
    }

    private static void check(long from, long to) {
        if (from > to) {
            throw new IllegalArgumentException("from > to");
        }
    }
}
