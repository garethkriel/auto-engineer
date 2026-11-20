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
        if (from == to) return;
        Map.Entry<Long, Long> f = map.floorEntry(from);
        if (f != null && f.getValue() >= from) {
            from = f.getKey();
            to = Math.max(to, f.getValue());
        }
        // remove all ranges starting within [from, to] (touching ones merge)
        var sub = map.subMap(from, true, to, true);
        for (Long end : sub.values()) to = Math.max(to, end);
        sub.clear();
        map.put(from, to);
    }

    public void remove(long from, long to) {
        check(from, to);
        if (from == to) return;
        Map.Entry<Long, Long> f = map.lowerEntry(from);
        if (f != null && f.getValue() > from) {
            long end = f.getValue();
            map.put(f.getKey(), from);
            if (end > to) map.put(to, end);
        }
        var sub = map.subMap(from, true, to, false);
        Long tailEnd = null;
        long tailStart = 0;
        for (Map.Entry<Long, Long> e : sub.entrySet()) {
            if (e.getValue() > to) {
                tailEnd = e.getValue();
                tailStart = to;
            }
        }
        sub.clear();
        if (tailEnd != null) map.put(tailStart, tailEnd);
    }

    public boolean contains(long x) {
        Map.Entry<Long, Long> f = map.floorEntry(x);
        return f != null && x < f.getValue();
    }

    public List<long[]> ranges() {
        List<long[]> out = new ArrayList<>(map.size());
        for (Map.Entry<Long, Long> e : map.entrySet()) out.add(new long[] {e.getKey(), e.getValue()});
        return out;
    }

    public long size() {
        long total = 0;
        for (Map.Entry<Long, Long> e : map.entrySet()) {
            long len = e.getValue() - e.getKey(); // wraps; negative means >= 2^63
            if (len < 0) return Long.MAX_VALUE;
            if (total > Long.MAX_VALUE - len) return Long.MAX_VALUE;
            total += len;
        }
        return total;
    }

    private static void check(long from, long to) {
        if (from > to) throw new IllegalArgumentException("from > to");
    }
}
