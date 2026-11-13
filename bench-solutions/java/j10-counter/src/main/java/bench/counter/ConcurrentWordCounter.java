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

/** Counts words with per-chunk local maps merged at the end. */
public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() {}

    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) throw new IllegalArgumentException("bad argument");
        List<String> snapshot = new ArrayList<>(lines);
        int n = snapshot.size();
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Map<String, Long>>> fs = new ArrayList<>();
            int chunk = Math.max(1, (n + threads - 1) / threads);
            for (int start = 0; start < n; start += chunk) {
                final int s = start, e = Math.min(n, start + chunk);
                fs.add(ex.submit(() -> {
                    Map<String, Long> m = new HashMap<>();
                    for (int i = s; i < e; i++) {
                        for (String w : snapshot.get(i).split("\\s+")) {
                            if (!w.isEmpty()) m.merge(w.toLowerCase(Locale.ROOT), 1L, Long::sum);
                        }
                    }
                    return m;
                }));
            }
            Map<String, Long> total = new HashMap<>();
            for (Future<Map<String, Long>> f : fs) {
                f.get().forEach((k, v) -> total.merge(k, v, Long::sum));
            }
            return total;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ie);
        } catch (ExecutionException ee) {
            throw new IllegalStateException(ee.getCause());
        } finally {
            ex.shutdownNow();
        }
    }
}
