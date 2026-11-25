package bench.counter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Word counting split across worker threads. Each worker fills its own map; the
 * caller joins every worker (so none is alive on return) and merges the maps.
 */
public final class ConcurrentWordCounter {
    private ConcurrentWordCounter() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Long> count(List<String> lines, int threads) {
        if (lines == null || threads < 1) {
            throw new IllegalArgumentException("invalid arguments");
        }
        List<String> snapshot = new ArrayList<>(lines);
        int n = snapshot.size();
        int workers = Math.max(1, Math.min(threads, n));
        Map<String, Long>[] partial = new Map[workers];
        Thread[] ts = new Thread[workers];
        boolean interrupted = false;
        try {
            for (int w = 0; w < workers; w++) {
                final int idx = w;
                final int from = (int) ((long) n * w / workers);
                final int to = (int) ((long) n * (w + 1) / workers);
                ts[w] = new Thread(() -> partial[idx] = countRange(snapshot, from, to), "word-counter-" + w);
                ts[w].start();
            }
        } finally {
            for (Thread t : ts) {
                if (t == null) {
                    continue;
                }
                for (;;) {
                    try {
                        t.join();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Long> m : partial) {
            if (m == null) {
                throw new IllegalStateException("worker failed");
            }
            for (Map.Entry<String, Long> e : m.entrySet()) {
                result.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        return result;
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
