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

/** Multi-threaded word counter with per-worker maps merged at the end. */
public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() { }

    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) {
            throw new IllegalArgumentException("bad argument");
        }
        List<String> snapshot = new ArrayList<>(lines);
        int n = snapshot.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Map<String, Long>>> futures = new ArrayList<>();
            int chunk = (n + threads - 1) / threads;
            for (int start = 0; start < n; start += Math.max(chunk, 1)) {
                final int from = start;
                final int to = Math.min(n, start + chunk);
                futures.add(pool.submit(() -> {
                    Map<String, Long> m = new HashMap<>();
                    for (int i = from; i < to; i++) {
                        for (String w : snapshot.get(i).split("\\s+")) {
                            if (!w.isEmpty()) {
                                m.merge(w.toLowerCase(Locale.ROOT), 1L, Long::sum);
                            }
                        }
                    }
                    return m;
                }));
            }
            Map<String, Long> result = new HashMap<>();
            for (Future<Map<String, Long>> f : futures) {
                f.get().forEach((k, v) -> result.merge(k, v, Long::sum));
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(c);
        } finally {
            pool.shutdownNow();
        }
    }
}
