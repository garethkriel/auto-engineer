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

/** Word counting split across worker threads, merged deterministically. */
public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() { }

    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) {
            throw new IllegalArgumentException("bad arguments");
        }
        List<String> snapshot = new ArrayList<>(lines);
        for (String l : snapshot) {
            if (l == null) {
                throw new IllegalArgumentException("null line");
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            int n = snapshot.size();
            int chunk = Math.max(1, (n + threads - 1) / threads);
            List<Future<Map<String, Long>>> futures = new ArrayList<>();
            for (int start = 0; start < n; start += chunk) {
                final int from = start;
                final int to = Math.min(n, start + chunk);
                futures.add(pool.submit(() -> countRange(snapshot, from, to)));
            }
            Map<String, Long> total = new HashMap<>();
            for (Future<Map<String, Long>> f : futures) {
                for (Map.Entry<String, Long> e : f.get().entrySet()) {
                    total.merge(e.getKey(), e.getValue(), Long::sum);
                }
            }
            return total;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Long> countRange(List<String> lines, int from, int to) {
        Map<String, Long> m = new HashMap<>();
        for (int i = from; i < to; i++) {
            for (String w : lines.get(i).split("\\s+")) {
                if (!w.isEmpty()) {
                    m.merge(w.toLowerCase(Locale.ROOT), 1L, Long::sum);
                }
            }
        }
        return m;
    }
}
