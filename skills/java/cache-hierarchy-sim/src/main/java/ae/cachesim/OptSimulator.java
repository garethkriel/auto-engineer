package ae.cachesim;

import java.util.HashMap;
import java.util.Map;

/** Belady's optimal replacement for a fully associative cache: the minimum possible miss count. */
public final class OptSimulator {
    private OptSimulator() {}

    /** Misses of the optimal policy for a trace of line numbers and a capacity in lines. */
    public static long misses(long[] lines, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        int n = lines.length;
        int[] next = new int[n]; // index of the next use of lines[i], or n when never used again
        Map<Long, Integer> seen = new HashMap<>();
        for (int i = n - 1; i >= 0; i--) {
            next[i] = seen.getOrDefault(lines[i], n);
            seen.put(lines[i], i);
        }
        Map<Long, Integer> nextUse = new HashMap<>(); // resident line -> index of its next use
        long misses = 0;
        for (int i = 0; i < n; i++) {
            long l = lines[i];
            if (nextUse.containsKey(l)) {
                nextUse.put(l, next[i]);
                continue;
            }
            misses++;
            if (nextUse.size() == capacity) {
                long victim = 0;
                int far = -1;
                for (Map.Entry<Long, Integer> e : nextUse.entrySet()) {
                    if (e.getValue() > far) {
                        far = e.getValue();
                        victim = e.getKey();
                    }
                }
                nextUse.remove(victim);
            }
            nextUse.put(l, next[i]);
        }
        return misses;
    }
}
