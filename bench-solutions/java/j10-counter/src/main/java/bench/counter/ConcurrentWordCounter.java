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
import java.util.concurrent.TimeUnit;

/** Counts words using worker threads, each with a private map merged at the end. */
public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() {}

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
                int start = (int) ((long) n * w / workers);
                int end = (int) ((long) n * (w + 1) / workers);
                List<String> part = snapshot.subList(start, end);
                futures.add(pool.submit(() -> countSequential(part)));
            }
            Map<String, Long> result = new HashMap<>();
            for (Future<Map<String, Long>> f : futures) {
                try {
                    f.get().forEach((k, v) -> result.merge(k, v, Long::sum));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            return result;
        } finally {
            pool.shutdownNow();
            boolean interrupted = false;
            while (true) {
                try {
                    if (pool.awaitTermination(1, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Map<String, Long> countSequential(List<String> lines) {
        Map<String, Long> m = new HashMap<>();
        for (String line : lines) {
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
