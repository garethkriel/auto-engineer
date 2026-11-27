package ae.xsort;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ReplacementSelectionTest {
    private static List<List<Integer>> runs(List<Integer> input, int m) {
        List<List<Integer>> out = new ArrayList<>();
        ReplacementSelection.generate(input.iterator(), Comparator.<Integer>naturalOrder(), m,
                new ReplacementSelection.RunSink<Integer>() {
                    @Override public void startRun() { out.add(new ArrayList<>()); }
                    @Override public void accept(Integer v) { out.get(out.size() - 1).add(v); }
                });
        return out;
    }

    @Test
    void literalExampleWithMemoryThree() {
        // Hand trace: heap {5,1,9}; pop 1 (read 2 ok), pop 2 (read 8 ok), pop 5 (read 3 < 5 -> run 1),
        // pop 8 (read 7 < 8 -> run 1), pop 9 (read 4 < 9 -> run 1), then run 1 drains 3, read 6, 4, 6, 7.
        assertEquals(List.of(List.of(1, 2, 5, 8, 9), List.of(3, 4, 6, 7)),
                runs(List.of(5, 1, 9, 2, 8, 3, 7, 4, 6), 3));
    }

    @Test
    void emptyInputMakesNoRuns() {
        assertEquals(List.of(), runs(List.of(), 4));
    }

    @Test
    void sortedInputIsOneRunWhateverTheMemory() {
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < 100; i++) in.add(i / 3); // non-decreasing with ties
        for (int m : new int[] {1, 2, 7, 100, 500}) assertEquals(1, runs(in, m).size(), "m=" + m);
    }

    @Test
    void reverseSortedInputGivesRunsOfExactlyM() {
        // n = 23, M = 5: runs of 5,5,5,5,3 (every new record is smaller than the last written one)
        List<Integer> in = new ArrayList<>();
        for (int i = 23; i >= 1; i--) in.add(i);
        List<List<Integer>> r = runs(in, 5);
        assertEquals(5, r.size());
        assertEquals(List.of(5, 5, 5, 5, 3), r.stream().map(List::size).toList());
    }

    @Test
    void everyRunSortedUnionIsInputAndRandomRunsAverageAboutTwoM() {
        Random rnd = new Random(7);
        int m = 50, n = 20000;
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < n; i++) in.add(rnd.nextInt(1_000_000));
        List<List<Integer>> r = runs(in, m);
        List<Integer> union = new ArrayList<>();
        for (List<Integer> run : r) {
            for (int i = 1; i < run.size(); i++) assertTrue(run.get(i - 1) <= run.get(i));
            union.addAll(run);
        }
        List<Integer> a = new ArrayList<>(union), b = new ArrayList<>(in);
        Collections.sort(a);
        Collections.sort(b);
        assertEquals(b, a);
        double avg = (double) n / r.size();
        assertTrue(avg > 1.6 * m, "average run length " + avg + " should be near 2M=" + 2 * m);
        assertTrue(avg < 2.4 * m, "average run length " + avg);
    }

    @Test
    void peakMemoryNeverExceedsM() {
        Random rnd = new Random(3);
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < 500; i++) in.add(rnd.nextInt(100));
        for (int m : new int[] {1, 2, 9, 40}) {
            int peak = ReplacementSelection.generate(in.iterator(), Comparator.<Integer>naturalOrder(), m,
                    new ReplacementSelection.RunSink<Integer>() {
                        @Override public void startRun() {}
                        @Override public void accept(Integer v) {}
                    });
            assertEquals(m, peak, "memory fills to M for inputs longer than M, m=" + m);
        }
        int peak = ReplacementSelection.generate(List.of(1, 2).iterator(), Comparator.<Integer>naturalOrder(), 10,
                new ReplacementSelection.RunSink<Integer>() {
                    @Override public void startRun() {}
                    @Override public void accept(Integer v) {}
                });
        assertEquals(2, peak);
    }

    record P(int key, int seq) {}

    @Test
    void tiesKeepArrivalOrderWithinAndAcrossRuns() {
        Random rnd = new Random(11);
        for (int trial = 0; trial < 100; trial++) {
            int n = 1 + rnd.nextInt(60), m = 1 + rnd.nextInt(6);
            List<P> in = new ArrayList<>();
            for (int i = 0; i < n; i++) in.add(new P(rnd.nextInt(4), i));
            List<List<P>> out = new ArrayList<>();
            ReplacementSelection.generate(in.iterator(), Comparator.comparingInt(P::key), m,
                    new ReplacementSelection.RunSink<P>() {
                        @Override public void startRun() { out.add(new ArrayList<>()); }
                        @Override public void accept(P v) { out.get(out.size() - 1).add(v); }
                    });
            for (List<P> run : out) {
                for (int i = 1; i < run.size(); i++) {
                    P x = run.get(i - 1), y = run.get(i);
                    assertTrue(x.key() < y.key() || (x.key() == y.key() && x.seq() < y.seq()),
                            "run not stably sorted: " + run);
                }
            }
            // cross-run: scanning runs in creation order, equal keys must appear in arrival order
            for (int k = 0; k < 4; k++) {
                int lastSeq = -1;
                for (int r = 0; r < out.size(); r++)
                    for (P p : out.get(r))
                        if (p.key() == k) {
                            assertTrue(p.seq() > lastSeq,
                                    "key " + k + " seq " + p.seq() + " in run " + r + " follows seq " + lastSeq);
                            lastSeq = p.seq();
                        }
            }
        }
    }
}
