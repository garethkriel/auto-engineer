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
import java.util.regex.Pattern;

/** Counts words with per-worker maps merged after all workers finish. */
public final class ConcurrentWordCounter {
    private static final Pattern WS = Pattern.compile("\\s+");

    private ConcurrentWordCounter() {
    }

    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) {
            throw new IllegalArgumentException("lines must be non-null and threads >= 1");
        }
        List<String> snapshot = new ArrayList<>(lines);
        int n = snapshot.size();
        int workers = Math.max(1, Math.min(threads, n));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Map<String, Long>>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                int lo = (int) ((long) n * w / workers);
                int hi = (int) ((long) n * (w + 1) / workers);
                List<String> chunk = snapshot.subList(lo, hi);
                futures.add(pool.submit(() -> countChunk(chunk)));
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
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(c);
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Map<String, Long> countChunk(List<String> chunk) {
        Map<String, Long> m = new HashMap<>();
        for (String line : chunk) {
            if (line == null) {
                continue;
            }
            for (String piece : WS.split(line)) {
                if (!piece.isEmpty()) {
                    m.merge(piece.toLowerCase(Locale.ROOT), 1L, Long::sum);
                }
            }
        }
        return m;
    }
}
