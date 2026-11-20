package bench.counter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Counts words using several worker threads with per-thread maps merged at the end. */
public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() { }

    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) {
            throw new IllegalArgumentException("invalid arguments");
        }
        List<String> copy = new ArrayList<>(lines);
        int n = copy.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Map<String, Long>>> futures = new ArrayList<>();
            int chunk = Math.max(1, (n + threads - 1) / threads);
            for (int start = 0; start < n; start += chunk) {
                final int s = start;
                final int e = Math.min(n, start + chunk);
                futures.add(pool.submit(() -> countRange(copy, s, e)));
            }
            Map<String, Long> result = new HashMap<>();
            for (Future<Map<String, Long>> f : futures) {
                f.get().forEach((k, v) -> result.merge(k, v, Long::sum));
            }
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException(ex.getCause());
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Map<String, Long> countRange(List<String> lines, int from, int to) {
        Map<String, Long> m = new HashMap<>();
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            for (String w : line.split("\\s+")) {
                if (!w.isEmpty()) {
                    m.merge(w.toLowerCase(Locale.ROOT), 1L, Long::sum);
                }
            }
        }
        return m;
    }
}
