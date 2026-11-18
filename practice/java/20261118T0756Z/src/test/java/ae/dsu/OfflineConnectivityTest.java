package ae.dsu;

import static ae.dsu.OfflineConnectivity.answer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ae.dsu.OfflineConnectivity.Add;
import ae.dsu.OfflineConnectivity.Event;
import ae.dsu.OfflineConnectivity.Query;
import ae.dsu.OfflineConnectivity.Remove;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OfflineConnectivityTest {
    @Test
    void addQueryRemoveQuery() {
        List<Event> ev = List.of(
                new Query(0, 1),   // false
                new Add(0, 1),
                new Query(1, 0),   // true (undirected)
                new Remove(1, 0),
                new Query(0, 1));  // false
        assertEquals(List.of(false, true, false), answer(2, ev));
    }

    @Test
    void pathThroughSeveralEdgesBreaksWhenMiddleEdgeRemoved() {
        List<Event> ev = List.of(
                new Add(0, 1), new Add(1, 2), new Add(2, 3),
                new Query(0, 3),   // true
                new Remove(1, 2),
                new Query(0, 3),   // false
                new Query(0, 1),   // true
                new Add(1, 3),
                new Query(0, 2));  // 0-1,1-3,3-2 -> true
        assertEquals(List.of(true, false, true, true), answer(4, ev));
    }

    @Test
    void duplicateEdgesAreCounted() {
        List<Event> ev = List.of(
                new Add(0, 1), new Add(0, 1),
                new Remove(0, 1),
                new Query(0, 1),   // still one copy -> true
                new Remove(0, 1),
                new Query(0, 1));  // false
        assertEquals(List.of(true, false), answer(2, ev));
    }

    @Test
    void edgeReAddedLater() {
        List<Event> ev = List.of(
                new Add(0, 1), new Remove(0, 1), new Query(0, 1),
                new Add(0, 1), new Query(0, 1));
        assertEquals(List.of(false, true), answer(2, ev));
    }

    @Test
    void selfLoopsAndSelfQueries() {
        List<Event> ev = List.of(new Add(1, 1), new Query(1, 1), new Query(0, 1), new Remove(1, 1));
        assertEquals(List.of(true, false), answer(2, ev));
    }

    @Test
    void emptyAndQueryFreeTimelines() {
        assertEquals(List.of(), answer(3, List.of()));
        assertEquals(List.of(), answer(3, List.of(new Add(0, 1), new Remove(0, 1))));
        assertEquals(List.of(), answer(0, List.of()));
    }

    @Test
    void removingAbsentEdgeFails() {
        assertThrows(IllegalArgumentException.class, () -> answer(2, List.of(new Remove(0, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> answer(2, List.of(new Add(0, 1), new Remove(0, 1), new Remove(0, 1))));
    }

    @Test
    void invalidVerticesAndNulls() {
        assertThrows(IllegalArgumentException.class, () -> answer(2, List.of(new Add(0, 2))));
        assertThrows(IllegalArgumentException.class, () -> answer(2, List.of(new Query(-1, 0))));
        assertThrows(IllegalArgumentException.class, () -> answer(-1, List.of()));
        assertThrows(NullPointerException.class, () -> answer(2, null));
    }

    /** Naive oracle: rebuild adjacency from the live edge multiset and BFS at every query. */
    private static List<Boolean> naive(int n, List<Event> events) {
        List<int[]> live = new ArrayList<>();
        List<Boolean> out = new ArrayList<>();
        for (Event e : events) {
            switch (e) {
                case Add a -> live.add(new int[] {a.u(), a.v()});
                case Remove r -> {
                    for (int i = 0; i < live.size(); i++) {
                        int[] x = live.get(i);
                        if ((x[0] == r.u() && x[1] == r.v()) || (x[0] == r.v() && x[1] == r.u())) {
                            live.remove(i);
                            break;
                        }
                    }
                }
                case Query q -> {
                    List<List<Integer>> adj = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        adj.add(new ArrayList<>());
                    }
                    for (int[] x : live) {
                        adj.get(x[0]).add(x[1]);
                        adj.get(x[1]).add(x[0]);
                    }
                    boolean[] seen = new boolean[n];
                    Deque<Integer> queue = new ArrayDeque<>();
                    queue.add(q.u());
                    seen[q.u()] = true;
                    while (!queue.isEmpty()) {
                        int cur = queue.poll();
                        for (int nx : adj.get(cur)) {
                            if (!seen[nx]) {
                                seen[nx] = true;
                                queue.add(nx);
                            }
                        }
                    }
                    out.add(seen[q.v()]);
                }
            }
        }
        return out;
    }

    private static List<Event> randomTimeline(Random rnd, int n, int steps) {
        List<Event> ev = new ArrayList<>();
        List<int[]> live = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            int kind = rnd.nextInt(10);
            if (kind < 4 || (kind < 7 && live.isEmpty())) {
                int u = rnd.nextInt(n);
                int v = rnd.nextInt(n);
                ev.add(new Add(u, v));
                live.add(new int[] {u, v});
            } else if (kind < 7) {
                int[] x = live.remove(rnd.nextInt(live.size()));
                ev.add(rnd.nextBoolean() ? new Remove(x[0], x[1]) : new Remove(x[1], x[0]));
            } else {
                ev.add(new Query(rnd.nextInt(n), rnd.nextInt(n)));
            }
        }
        return ev;
    }

    @Test
    void randomisedAgainstNaiveOracle() {
        Random rnd = new Random(20261118L);
        for (int round = 0; round < 300; round++) {
            int n = 1 + rnd.nextInt(8);
            List<Event> ev = randomTimeline(rnd, n, rnd.nextInt(60));
            assertEquals(naive(n, ev), answer(n, ev), "round " + round + " n=" + n + " events=" + ev);
        }
    }

    @Test
    void largeTimelineIsFast() {
        Random rnd = new Random(7);
        int n = 2000;
        List<Event> ev = randomTimeline(rnd, n, 20_000);
        List<Boolean> got = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> answer(n, ev));
        long queries = ev.stream().filter(e -> e instanceof Query).count();
        assertEquals(queries, got.size());
        // spot-check the first 300 events against the oracle
        List<Event> prefix = ev.subList(0, 300);
        assertEquals(naive(n, prefix), answer(n, prefix));
    }
}
