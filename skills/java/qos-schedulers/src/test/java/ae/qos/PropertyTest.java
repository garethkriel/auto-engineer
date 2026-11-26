package ae.qos;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Seeded randomized properties and independent oracles. */
class PropertyTest {
    static final String[] FLOWS = {"A", "B", "C", "D"};

    static List<Packet> workload(Random rnd, int n, int maxSize, int maxGap, int flows) {
        List<Packet> out = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < n; i++) {
            t += rnd.nextInt(maxGap + 1);
            out.add(new Packet(i, FLOWS[rnd.nextInt(flows)], 1 + rnd.nextInt(maxSize), t));
        }
        return out;
    }

    static Map<String, Integer> table(Random rnd, int flows, int lo, int hi) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < flows; i++) m.put(FLOWS[i], lo + rnd.nextInt(hi - lo + 1));
        return m;
    }

    @Test
    void everySchedulerSatisfiesAllInvariantsOnRandomWorkloads() {
        Random rnd = new Random(7);
        int rounds = 0;
        for (int round = 0; round < 150; round++) {
            int flows = 1 + rnd.nextInt(4);
            List<Packet> in = workload(rnd, 40 + rnd.nextInt(40), 1 + rnd.nextInt(400), rnd.nextInt(6), flows);
            long rate = 1 + rnd.nextInt(60);
            long buf = rnd.nextBoolean() ? Long.MAX_VALUE : 200 + rnd.nextInt(1500);
            List<Supplier<Scheduler>> makers = List.of(
                    FifoScheduler::new,
                    () -> new PriorityScheduler(table(rnd, flows, 0, 2)),
                    () -> new DrrScheduler(table(rnd, flows, 1, 300)),
                    () -> new WfqScheduler(table(rnd, flows, 1, 9)));
            for (Supplier<Scheduler> mk : makers) {
                Scheduler s = mk.get();
                LinkSimulator.Result r = LinkSimulator.run(in, s, rate, buf);
                assertEquals(List.of(), Invariants.check(in, r, rate), s.getClass().getSimpleName() + " round " + round);
                assertTrue(s.isEmpty());
                assertEquals(in.size(), r.departures().size() + r.drops().size());
            }
            rounds++;
        }
        assertEquals(150, rounds);
    }

    @Test
    void unlimitedBufferNeverDropsAndLimitedBufferSometimesDoes() {
        Random rnd = new Random(11);
        int dropRuns = 0;
        for (int round = 0; round < 60; round++) {
            List<Packet> in = workload(rnd, 60, 300, 1, 2);
            assertTrue(LinkSimulator.run(in, new FifoScheduler(), 20, Long.MAX_VALUE).drops().isEmpty());
            LinkSimulator.Result r = LinkSimulator.run(in, new FifoScheduler(), 20, 400);
            if (!r.drops().isEmpty()) dropRuns++;
        }
        assertTrue(dropRuns >= 30, "drop runs " + dropRuns); // overloaded: 150 B/arrival on average vs 20 B/tick
    }

    /** Offline DRR oracle: everything enqueued at once, run literal rounds over a list of flows. */
    static List<Integer> drrOracle(List<Packet> in, Map<String, Integer> quanta) {
        Map<String, List<Packet>> byFlow = new LinkedHashMap<>();
        for (Packet p : in) byFlow.computeIfAbsent(p.flow(), k -> new ArrayList<>()).add(p);
        Map<String, Long> deficit = new HashMap<>();
        Map<String, Integer> next = new HashMap<>();
        List<String> active = new ArrayList<>(byFlow.keySet()); // order of first packet == order of becoming active
        List<Integer> out = new ArrayList<>();
        while (!active.isEmpty()) {
            List<String> still = new ArrayList<>();
            for (String f : active) {
                List<Packet> q = byFlow.get(f);
                long d = deficit.getOrDefault(f, 0L) + quanta.get(f);
                int i = next.getOrDefault(f, 0);
                while (i < q.size() && q.get(i).size() <= d) {
                    d -= q.get(i).size();
                    out.add(q.get(i).id());
                    i++;
                }
                next.put(f, i);
                if (i < q.size()) {
                    deficit.put(f, d);
                    still.add(f);
                }
            }
            active = still;
        }
        return out;
    }

    @Test
    void drrMatchesOfflineRoundOracleOnBacklogs() {
        Random rnd = new Random(21);
        for (int round = 0; round < 200; round++) {
            int flows = 1 + rnd.nextInt(4);
            List<Packet> in = workload(rnd, 5 + rnd.nextInt(60), 1 + rnd.nextInt(500), 0, flows);
            Map<String, Integer> q = table(rnd, flows, 1, 400);
            DrrScheduler s = new DrrScheduler(q);
            for (Packet p : in) s.enqueue(p);
            List<Integer> got = SchedulerTraceTest.drain(s);
            // flows that never occur are simply absent; oracle quanta lookup only touches present flows
            assertEquals(drrOracle(in, q), got, "round " + round);
        }
    }

    /** Naive SCFQ oracle: recompute everything from the enqueue log, scanning all queued packets for the minimum. */
    static List<Integer> wfqOracle(List<Packet> in, Map<String, Integer> w, List<int[]> script) {
        long scale = 1;
        for (int x : w.values()) scale = scale / gcd(scale, x) * x;
        Map<String, Long> last = new HashMap<>();
        List<long[]> queued = new ArrayList<>(); // {tag, seq, id}
        List<Integer> out = new ArrayList<>();
        long v = 0;
        int seq = 0;
        int cursor = 0;
        for (int[] step : script) { // step[0] = number of enqueues, then one dequeue (if step[1] == 1)
            for (int k = 0; k < step[0]; k++) {
                Packet p = in.get(cursor++);
                long start = Math.max(last.getOrDefault(p.flow(), 0L), v);
                long fin = start + p.size() * (scale / w.get(p.flow()));
                last.put(p.flow(), fin);
                queued.add(new long[] {fin, seq++, p.id()});
            }
            if (step[1] == 1 && !queued.isEmpty()) {
                long[] best = queued.get(0);
                for (long[] c : queued) if (c[0] < best[0] || (c[0] == best[0] && c[1] < best[1])) best = c;
                queued.remove(best);
                v = best[0];
                out.add((int) best[2]);
            }
        }
        return out;
    }

    static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    @Test
    void wfqMatchesNaiveOracleWithInterleavedEnqueuesAndDequeues() {
        Random rnd = new Random(31);
        for (int round = 0; round < 200; round++) {
            int flows = 1 + rnd.nextInt(4);
            int n = 10 + rnd.nextInt(50);
            List<Packet> in = workload(rnd, n, 1 + rnd.nextInt(300), 0, flows);
            Map<String, Integer> w = table(rnd, flows, 1, 12);
            List<int[]> script = new ArrayList<>();
            int left = n;
            while (left > 0) {
                int k = Math.min(left, 1 + rnd.nextInt(3));
                script.add(new int[] {k, rnd.nextInt(3) == 0 ? 0 : 1});
                left -= k;
            }
            for (int i = 0; i < n; i++) script.add(new int[] {0, 1});
            WfqScheduler s = new WfqScheduler(w);
            List<Integer> got = new ArrayList<>();
            int cursor = 0;
            for (int[] step : script) {
                for (int k = 0; k < step[0]; k++) s.enqueue(in.get(cursor++));
                if (step[1] == 1) s.dequeue().ifPresent(x -> got.add(x.id()));
            }
            assertEquals(wfqOracle(in, w, script), got, "round " + round);
            assertEquals(n, got.size());
        }
    }

    /** Bytes served per flow over the prefix of departures during which every flow still has packets waiting. */
    static Map<String, Long> prefixBytes(List<Packet> in, List<Integer> order, int flows) {
        Map<Integer, Packet> byId = new HashMap<>();
        Map<String, Integer> remaining = new HashMap<>();
        for (Packet p : in) {
            byId.put(p.id(), p);
            remaining.merge(p.flow(), 1, Integer::sum);
        }
        Map<String, Long> bytes = new LinkedHashMap<>();
        for (int i = 0; i < flows; i++) bytes.put(FLOWS[i], 0L);
        for (int id : order) {
            Packet p = byId.get(id);
            if (remaining.get(p.flow()) == 1) break; // this flow's last packet: it is no longer backlogged after it
            bytes.merge(p.flow(), (long) p.size(), Long::sum);
            remaining.merge(p.flow(), -1, Integer::sum);
        }
        return bytes;
    }

    static List<Packet> backlog(Random rnd, int flows, int perFlow, int maxSize) {
        List<Packet> in = new ArrayList<>();
        int id = 0;
        for (int i = 0; i < perFlow * flows; i++) in.add(new Packet(id++, FLOWS[i % flows], 1 + rnd.nextInt(maxSize), 0));
        return in;
    }

    @Test
    void wfqFairnessBoundForBackloggedFlows() {
        Random rnd = new Random(41);
        long worstSlack = Long.MAX_VALUE;
        for (int round = 0; round < 150; round++) {
            int flows = 2 + rnd.nextInt(3);
            int maxSize = 50 + rnd.nextInt(400);
            List<Packet> in = backlog(rnd, flows, 80, maxSize);
            Map<String, Integer> w = table(rnd, flows, 1, 8);
            WfqScheduler s = new WfqScheduler(w);
            for (Packet p : in) s.enqueue(p);
            Map<String, Long> b = prefixBytes(in, SchedulerTraceTest.drain(s), flows);
            for (String i : b.keySet()) {
                for (String j : b.keySet()) {
                    long diff = Math.abs(b.get(i) * w.get(j) - b.get(j) * w.get(i));
                    long bound = (long) maxSize * (w.get(i) + w.get(j));
                    assertTrue(diff <= bound, "round " + round + " " + i + j + " diff " + diff + " bound " + bound);
                    worstSlack = Math.min(worstSlack, bound - diff);
                }
            }
        }
        assertTrue(worstSlack >= 0);
    }

    @Test
    void drrFairnessBoundForBackloggedFlows() {
        Random rnd = new Random(51);
        for (int round = 0; round < 150; round++) {
            int flows = 2 + rnd.nextInt(3);
            int maxSize = 50 + rnd.nextInt(400);
            List<Packet> in = backlog(rnd, flows, 80, maxSize);
            Map<String, Integer> q = table(rnd, flows, 1, 600);
            DrrScheduler s = new DrrScheduler(q);
            for (Packet p : in) s.enqueue(p);
            Map<String, Long> b = prefixBytes(in, SchedulerTraceTest.drain(s), flows);
            // Per flow B = r*q - d (0 <= d < Lmax at turn ends) plus at most one partial turn of <= q + Lmax bytes,
            // and the turn counts of backlogged flows differ by at most 1: |B_i/q_i - B_j/q_j| <= 2 + 2*Lmax/q_i + 2*Lmax/q_j.
            for (String i : b.keySet()) {
                for (String j : b.keySet()) {
                    long qi = q.get(i), qj = q.get(j);
                    long diff = Math.abs(b.get(i) * qj - b.get(j) * qi); // scaled by qi*qj
                    long bound = 2 * qi * qj + 2L * maxSize * (qi + qj);
                    assertTrue(diff <= bound, "round " + round + " " + i + j + " diff " + diff + " bound " + bound);
                }
            }
        }
    }

    @Test
    void priorityStarvesAndWfqDoesNotOnTheSameBacklog() {
        List<Packet> in = new ArrayList<>();
        for (int i = 0; i < 20; i++) in.add(new Packet(i, i < 10 ? "hi" : "lo", 100, 0));
        PriorityScheduler pr = new PriorityScheduler(Map.of("hi", 0, "lo", 1));
        for (Packet p : in) pr.enqueue(p);
        List<Integer> order = SchedulerTraceTest.drain(pr);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), order.subList(0, 10));
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("hi", 1);
        w.put("lo", 1);
        WfqScheduler f = new WfqScheduler(w);
        for (Packet p : in) f.enqueue(p);
        List<Integer> fair = SchedulerTraceTest.drain(f);
        assertTrue(fair.subList(0, 4).contains(10), fair.toString());
    }

    /** Tick-by-tick oracle: refill each tick (capped), release head packets while they fit. */
    static Map<Integer, Long> shaperOracle(List<Packet> in, long rate, long burst) {
        Map<String, List<Packet>> byFlow = new LinkedHashMap<>();
        for (Packet p : in.stream().sorted(java.util.Comparator.comparingLong(Packet::arrival)).toList()) {
            byFlow.computeIfAbsent(p.flow(), k -> new ArrayList<>()).add(p);
        }
        Map<Integer, Long> rel = new HashMap<>();
        for (List<Packet> q : byFlow.values()) {
            long tokens = burst;
            int i = 0;
            for (long t = 0; i < q.size(); t++) {
                if (t > 0) tokens = Math.min(burst, tokens + rate);
                while (i < q.size() && q.get(i).arrival() <= t && q.get(i).size() <= tokens) {
                    tokens -= q.get(i).size();
                    rel.put(q.get(i).id(), t);
                    i++;
                }
            }
        }
        return rel;
    }

    @Test
    void shaperMatchesTickOracleAndEnvelopeHolds() {
        Random rnd = new Random(61);
        for (int round = 0; round < 150; round++) {
            long rate = 1 + rnd.nextInt(20);
            long burst = 20 + rnd.nextInt(200);
            List<Packet> in = workload(rnd, 30, (int) burst, 12, 1 + rnd.nextInt(3));
            List<Packet> out = TokenBucketShaper.shape(in, rate, burst);
            Map<Integer, Long> oracle = shaperOracle(in, rate, burst);
            Map<String, List<Packet>> perFlow = new LinkedHashMap<>();
            for (Packet p : out) {
                assertEquals(oracle.get(p.id()), p.arrival(), "round " + round + " id " + p.id());
                perFlow.computeIfAbsent(p.flow(), k -> new ArrayList<>()).add(p);
            }
            for (int k = 1; k < out.size(); k++) assertTrue(out.get(k - 1).arrival() <= out.get(k).arrival());
            // token-bucket envelope: any run i..j of one flow's releases carries <= burst + rate * (t_j - t_i) bytes
            for (List<Packet> q : perFlow.values()) {
                for (int i = 0; i < q.size(); i++) {
                    long sum = 0;
                    for (int j = i; j < q.size(); j++) {
                        sum += q.get(j).size();
                        assertTrue(sum <= burst + rate * (q.get(j).arrival() - q.get(i).arrival()),
                                "round " + round + " window " + i + ".." + j);
                    }
                }
            }
        }
    }

    @Test
    void shapedTrafficKeepsPacketIdentityAndNeverReleasesEarly() {
        Random rnd = new Random(71);
        List<Packet> in = workload(rnd, 100, 100, 3, 3);
        List<Packet> out = TokenBucketShaper.shape(in, 5, 100);
        assertEquals(in.size(), out.size());
        Map<Integer, Packet> orig = new HashMap<>();
        for (Packet p : in) orig.put(p.id(), p);
        for (Packet p : out) {
            Packet o = orig.get(p.id());
            assertEquals(o.flow(), p.flow());
            assertEquals(o.size(), p.size());
            assertTrue(p.arrival() >= o.arrival());
        }
    }

    @Test
    void shapingAndSchedulingComposeEndToEnd() {
        Random rnd = new Random(81);
        List<Packet> in = workload(rnd, 120, 200, 2, 3);
        List<Packet> shaped = TokenBucketShaper.shape(in, 8, 200);
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("A", 3);
        w.put("B", 2);
        w.put("C", 1);
        LinkSimulator.Result r = LinkSimulator.run(shaped, new WfqScheduler(w), 20, Long.MAX_VALUE);
        assertEquals(List.of(), Invariants.check(shaped, r, 20));
        assertEquals(120, r.departures().size());
    }
}
