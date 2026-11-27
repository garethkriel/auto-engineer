package bench.ranges;

import java.util.*;

/** Set of longs stored as disjoint, non-adjacent half-open ranges. */
public final class LongRangeSet {
    private final TreeMap<Long, Long> map = new TreeMap<>();

    public LongRangeSet() {}

    public void add(long from, long to) {
        if (from > to) throw new IllegalArgumentException("from > to");
        if (from == to) return;
        Map.Entry<Long, Long> f = map.floorEntry(from);
        if (f != null && f.getValue() >= from) {
            from = f.getKey();
            to = Math.max(to, f.getValue());
            map.remove(f.getKey());
        }
        Map.Entry<Long, Long> e;
        while ((e = map.ceilingEntry(from)) != null && e.getKey() <= to) {
            to = Math.max(to, e.getValue());
            map.remove(e.getKey());
        }
        map.put(from, to);
    }

    public void remove(long from, long to) {
        if (from > to) throw new IllegalArgumentException("from > to");
        if (from == to) return;
        Map.Entry<Long, Long> f = map.floorEntry(from);
        if (f != null && f.getValue() > from) {
            long start = f.getKey(), end = f.getValue();
            if (start == from) map.remove(start); else map.put(start, from);
            if (end > to) map.put(to, end);
        }
        Map.Entry<Long, Long> e;
        while ((e = map.ceilingEntry(from)) != null && e.getKey() < to) {
            map.remove(e.getKey());
            if (e.getValue() > to) map.put(to, e.getValue());
        }
    }

    public boolean contains(long x) {
        Map.Entry<Long, Long> f = map.floorEntry(x);
        return f != null && x < f.getValue();
    }

    public List<long[]> ranges() {
        List<long[]> r = new ArrayList<>(map.size());
        for (Map.Entry<Long, Long> e : map.entrySet()) r.add(new long[] {e.getKey(), e.getValue()});
        return r;
    }

    public long size() {
        long total = 0;
        for (Map.Entry<Long, Long> e : map.entrySet()) {
            long len = e.getValue() - e.getKey();
            if (len < 0) return Long.MAX_VALUE; // length exceeds Long.MAX_VALUE
            if (total > Long.MAX_VALUE - len) return Long.MAX_VALUE;
            total += len;
        }
        return total;
    }
}
