package bench.counter;

import java.util.*;
import java.util.concurrent.*;

public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() {}

    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) throw new IllegalArgumentException();
        List<String> copy = new ArrayList<>(lines);
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            int n = copy.size();
            int chunk = (n + threads - 1) / threads;
            List<Future<Map<String, Long>>> fs = new ArrayList<>();
            for (int s = 0; s < n; s += Math.max(chunk, 1)) {
                final int from = s, to = Math.min(n, s + chunk);
                fs.add(ex.submit(() -> {
                    Map<String, Long> m = new HashMap<>();
                    for (int i = from; i < to; i++) {
                        String line = copy.get(i);
                        if (line == null) continue;
                        for (String w : line.split("\\s+")) {
                            if (!w.isEmpty()) m.merge(w.toLowerCase(Locale.ROOT), 1L, Long::sum);
                        }
                    }
                    return m;
                }));
            }
            Map<String, Long> total = new HashMap<>();
            for (Future<Map<String, Long>> f : fs) {
                try {
                    f.get().forEach((k, v) -> total.merge(k, v, Long::sum));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            return total;
        } finally {
            ex.shutdownNow();
        }
    }
}
