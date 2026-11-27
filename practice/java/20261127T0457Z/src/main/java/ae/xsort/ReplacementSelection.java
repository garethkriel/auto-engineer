package ae.xsort;

import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;

/**
 * Run generation by replacement selection. A heap of at most {@code m} records is ordered by
 * (run number, key, arrival sequence). After the smallest record is written, the next input record
 * joins the current run if it is not smaller than the one just written, otherwise the next run.
 * Sorted input yields one run, reverse-sorted input runs of exactly m, random input about 2m.
 */
final class ReplacementSelection {
    private ReplacementSelection() {}

    interface RunSink<T> {
        /** Called before the first record of every run (never for an empty run). */
        void startRun();

        void accept(T value);
    }

    private record Entry<T>(int run, long seq, T value) {}

    /** Returns the peak number of records held in memory (the heap size). */
    static <T> int generate(Iterator<T> input, Comparator<? super T> cmp, int m, RunSink<T> sink) {
        if (m < 1) throw new IllegalArgumentException("memory must hold at least 1 record");
        Comparator<Entry<T>> order = (a, b) -> {
            if (a.run() != b.run()) return Integer.compare(a.run(), b.run());
            int c = cmp.compare(a.value(), b.value());
            return c != 0 ? c : Long.compare(a.seq(), b.seq());
        };
        PriorityQueue<Entry<T>> heap = new PriorityQueue<>(Math.min(m, 1024) + 1, order);
        long seq = 0;
        while (heap.size() < m && input.hasNext()) heap.add(new Entry<>(0, seq++, input.next()));
        int peak = heap.size();
        int currentRun = -1;
        while (!heap.isEmpty()) {
            Entry<T> e = heap.poll();
            if (e.run() != currentRun) {
                currentRun = e.run();
                sink.startRun();
            }
            sink.accept(e.value());
            if (input.hasNext()) {
                T v = input.next();
                int run = cmp.compare(v, e.value()) < 0 ? currentRun + 1 : currentRun;
                heap.add(new Entry<>(run, seq++, v));
                peak = Math.max(peak, heap.size());
            }
        }
        return peak;
    }
}
