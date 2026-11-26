package bench.counter;

import java.util.*;
import java.util.concurrent.*;

/** Word count split across worker threads; per-worker maps merged at the end. */
public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() {}

    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) throw new IllegalArgumentException("bad argument");
        List<String> snapshot = new ArrayList<>(lines);
        int n = snapshot.size();
        int workers = Math.max(1, Math.min(threads, n));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Map<String, Long>>> futures = new ArrayList<>();
            int chunk = (n + workers - 1) / workers;
            for (int start = 0; start < n; start += chunk) {
                final int lo = start, hi = Math.min(n, start + chunk);
                futures.add(pool.submit(() -> countRange(snapshot, lo, hi)));
            }
            Map<String, Long> total = new HashMap<>();
            for (Future<Map<String, Long>> f : futures) {
                for (Map.Entry<String, Long> e : f.get().entrySet()) total.merge(e.getKey(), e.getValue(), Long::sum);
            }
            return total;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Long> countRange(List<String> lines, int lo, int hi) {
        Map<String, Long> m = new HashMap<>();
        for (int i = lo; i < hi; i++) {
            String line = lines.get(i);
            if (line == null) continue;
            for (String w : line.split("\\s+")) {
                if (!w.isEmpty()) m.merge(w.toLowerCase(Locale.ROOT), 1L, Long::sum);
            }
        }
        return m;
    }
}
