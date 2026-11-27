package bench.counter;

import java.util.*;
import java.util.concurrent.*;

public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() {}

    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) throw new IllegalArgumentException();
        List<String> snapshot = new ArrayList<>(lines);
        int n = snapshot.size();
        int workers = Math.max(1, Math.min(threads, n));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Map<String, Long>>> futures = new ArrayList<>();
            int chunk = (n + workers - 1) / workers;
            for (int start = 0; start < n; start += chunk) {
                final int s = start, e = Math.min(n, start + chunk);
                futures.add(pool.submit(() -> {
                    Map<String, Long> local = new HashMap<>();
                    for (int i = s; i < e; i++) {
                        String line = snapshot.get(i);
                        if (line == null) continue;
                        for (String w : line.split("\\s+")) {
                            if (!w.isEmpty()) local.merge(w.toLowerCase(Locale.ROOT), 1L, Long::sum);
                        }
                    }
                    return local;
                }));
            }
            Map<String, Long> result = new HashMap<>();
            for (Future<Map<String, Long>> f : futures) {
                try {
                    f.get().forEach((k, v) -> result.merge(k, v, Long::sum));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                } catch (ExecutionException ee) {
                    throw new IllegalStateException(ee.getCause());
                }
            }
            return result;
        } finally {
            pool.shutdownNow();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }
}
