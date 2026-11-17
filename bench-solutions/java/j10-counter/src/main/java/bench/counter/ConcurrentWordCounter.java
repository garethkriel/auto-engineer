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

/** Multi-threaded word counter; each worker counts a slice into a private map. */
public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() {}

    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) {
            throw new IllegalArgumentException("bad arguments");
        }
        List<String> snapshot = new ArrayList<>(lines);
        int n = snapshot.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Map<String, Long>>> futures = new ArrayList<>();
            int chunk = Math.max(1, (n + threads - 1) / threads);
            for (int start = 0; start < n; start += chunk) {
                final int s = start;
                final int e = Math.min(n, start + chunk);
                futures.add(pool.submit(() -> countSlice(snapshot.subList(s, e))));
            }
            Map<String, Long> result = new HashMap<>();
            for (Future<Map<String, Long>> f : futures) {
                for (Map.Entry<String, Long> en : f.get().entrySet()) {
                    result.merge(en.getKey(), en.getValue(), Long::sum);
                }
            }
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(ex.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Long> countSlice(List<String> slice) {
        Map<String, Long> m = new HashMap<>();
        for (String line : slice) {
            if (line == null) {
                throw new IllegalArgumentException("null line");
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
