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

/** Counts words with per-worker maps merged at the end (no shared mutable state). */
public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() { }

    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) {
            throw new IllegalArgumentException("invalid arguments");
        }
        List<String> snapshot = new ArrayList<>(lines);
        int n = snapshot.size();
        int workers = Math.max(1, Math.min(threads, n));
        List<Thread> created = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "word-count-worker");
            t.setDaemon(true);
            created.add(t);
            return t;
        });
        try {
            List<Future<Map<String, Long>>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                int lo = (int) ((long) n * w / workers);
                int hi = (int) ((long) n * (w + 1) / workers);
                List<String> part = snapshot.subList(lo, hi);
                futures.add(pool.submit(() -> countPart(part)));
            }
            Map<String, Long> result = new HashMap<>();
            for (Future<Map<String, Long>> f : futures) {
                for (Map.Entry<String, Long> e : f.get().entrySet()) {
                    result.merge(e.getKey(), e.getValue(), Long::sum);
                }
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
            boolean interrupted = false;
            while (!pool.isTerminated()) {
                try {
                    pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            for (Thread t : new ArrayList<>(created)) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Map<String, Long> countPart(List<String> part) {
        Map<String, Long> m = new HashMap<>();
        for (String line : part) {
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
