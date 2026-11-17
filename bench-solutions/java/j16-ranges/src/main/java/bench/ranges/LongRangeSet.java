package bench.ranges;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class LongRangeSet {
    private final TreeMap<Long, Long> map = new TreeMap<>();

    public LongRangeSet() {}

    public void add(long from, long to) {
        if (from > to) throw new IllegalArgumentException("from > to");
        if (from == to) return;
        Map.Entry<Long, Long> fl = map.floorEntry(from);
        if (fl != null && fl.getValue() >= from) {
            from = fl.getKey();
            to = Math.max(to, fl.getValue());
        }
        var sub = map.subMap(from, true, to, true);
        for (var it = sub.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            to = Math.max(to, e.getValue());
            it.remove();
        }
        map.put(from, to);
    }

    public void remove(long from, long to) {
        if (from > to) throw new IllegalArgumentException("from > to");
        if (from == to) return;
        Map.Entry<Long, Long> fl = map.lowerEntry(from);
        if (fl != null && fl.getValue() > from) {
            long end = fl.getValue();
            map.put(fl.getKey(), from);
            if (end > to) map.put(to, end);
        }
        var sub = map.subMap(from, true, to, false);
        long tail = Long.MIN_VALUE;
        boolean hasTail = false;
        for (var it = sub.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            if (e.getValue() > to) { tail = e.getValue(); hasTail = true; }
            it.remove();
        }
        if (hasTail) map.put(to, tail);
    }

    public boolean contains(long x) {
        Map.Entry<Long, Long> e = map.floorEntry(x);
        return e != null && x < e.getValue();
    }

    public List<long[]> ranges() {
        List<long[]> out = new ArrayList<>(map.size());
        for (var e : map.entrySet()) out.add(new long[] {e.getKey(), e.getValue()});
        return out;
    }

    public long size() {
        long total = 0;
        for (var e : map.entrySet()) {
            long len = e.getValue() - e.getKey(); // may wrap: treat as unsigned
            if (len < 0) return Long.MAX_VALUE;
            if (total > Long.MAX_VALUE - len) return Long.MAX_VALUE;
            total += len;
        }
        return total;
    }
}
