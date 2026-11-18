package bench.ranges;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Set of longs stored as disjoint, non-adjacent half-open ranges. */
public final class LongRangeSet {
    private final TreeMap<Long, Long> map = new TreeMap<>();

    public LongRangeSet() {
    }

    public void add(long from, long to) {
        check(from, to);
        if (from == to) {
            return;
        }
        Map.Entry<Long, Long> lo = map.floorEntry(from);
        if (lo != null && lo.getValue() >= from) {
            from = lo.getKey();
            to = Math.max(to, lo.getValue());
        }
        while (true) {
            Map.Entry<Long, Long> e = map.ceilingEntry(from);
            if (e == null || e.getKey() > to) {
                break;
            }
            to = Math.max(to, e.getValue());
            map.remove(e.getKey());
        }
        map.put(from, to);
    }

    public void remove(long from, long to) {
        check(from, to);
        if (from == to) {
            return;
        }
        Map.Entry<Long, Long> lo = map.lowerEntry(from);
        if (lo != null && lo.getValue() > from) {
            long k = lo.getKey();
            long v = lo.getValue();
            map.put(k, from);
            if (v > to) {
                map.put(to, v);
            }
        }
        while (true) {
            Map.Entry<Long, Long> e = map.ceilingEntry(from);
            if (e == null || e.getKey() >= to) {
                break;
            }
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
            long len = e.getValue() - e.getKey(); // unsigned length, wraps correctly
            if (len < 0) {
                return Long.MAX_VALUE;
            }
            if (total > Long.MAX_VALUE - len) {
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
