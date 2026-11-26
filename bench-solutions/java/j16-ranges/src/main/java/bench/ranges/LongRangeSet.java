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
        long start = from;
        long end = to;
        Map.Entry<Long, Long> lo = map.floorEntry(from);
        if (lo != null && lo.getValue() >= from) {
            start = lo.getKey();
            end = Math.max(end, lo.getValue());
        }
        // remove all ranges starting in [start, to] (adjacent included)
        var it = map.subMap(start, true, to, true).entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            end = Math.max(end, e.getValue());
            it.remove();
        }
        map.put(start, end);
    }

    public void remove(long from, long to) {
        check(from, to);
        if (from == to) {
            return;
        }
        Long first = map.floorKey(from);
        if (first == null) {
            first = from;
        }
        List<long[]> readd = new ArrayList<>();
        var it = map.subMap(first, true, to, false).entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            long s = e.getKey();
            long en = e.getValue();
            if (en <= from) {
                continue;
            }
            it.remove();
            if (s < from) {
                readd.add(new long[] {s, from});
            }
            if (en > to) {
                readd.add(new long[] {to, en});
            }
        }
        for (long[] r : readd) {
            map.put(r[0], r[1]);
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
            long len = e.getValue() - e.getKey(); // unsigned length, may wrap
            if (len < 0) {
                return Long.MAX_VALUE;
            }
            long next = total + len;
            if (next < 0) {
                return Long.MAX_VALUE;
            }
            total = next;
        }
        return total;
    }

    private static void check(long from, long to) {
        if (from > to) {
            throw new IllegalArgumentException("from > to");
        }
    }
}
