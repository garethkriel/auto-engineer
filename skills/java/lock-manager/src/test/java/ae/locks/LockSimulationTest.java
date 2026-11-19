package ae.locks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.locks.LockManager.Events;
import ae.locks.LockManager.Outcome;
import ae.locks.LockManager.Status;
import ae.locks.LockManager.TxnState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class LockSimulationTest {
    private static final String[] RESOURCES = {
        "db", "db/t1", "db/t2", "db/t1/r1", "db/t1/r2", "db/t1/r3", "db/t2/r1", "db/t2/r2",
    };
    private static final int TXNS = 8;

    /** Independent model: which transactions are BLOCKED, built only from reported outcomes. */
    private static final class Model {
        final Set<Long> blocked = new TreeSet<>();
        final Set<Long> live = new TreeSet<>();
        long aborts;
        long waits;

        void apply(LockManager m, Long requester, Events e) {
            for (long u : e.unblocked()) {
                assertTrue(blocked.remove(u), "unblocked " + u + " was not blocked in the model");
                assertEquals(TxnState.ACTIVE, m.state(u));
                assertTrue(requester == null || u != requester, "requester listed as unblocked");
            }
            for (long a : e.aborted()) {
                if (requester == null || a != requester) {
                    assertTrue(blocked.remove(a), "victim " + a + " was not blocked in the model");
                }
                live.remove(a);
                aborts++;
                assertEquals(TxnState.ABORTED, m.state(a));
            }
        }

        void compare(LockManager m, long totalBegun) {
            Set<Long> actual = new TreeSet<>();
            for (long id = 0; id < totalBegun; id++) {
                if (m.state(id) == TxnState.BLOCKED) {
                    actual.add(id);
                }
            }
            assertEquals(blocked, actual);
        }
    }

    private static boolean hasHeldDescendant(Map<String, LockMode> held, String r) {
        for (String other : held.keySet()) {
            if (other.startsWith(r + "/")) {
                return true;
            }
        }
        return false;
    }

    private static void runSeed(long seed, int steps) {
        Random rnd = new Random(seed);
        LockManager m = new LockManager();
        Model model = new Model();
        Set<Long> shrinking = new HashSet<>();
        int begun = 0;
        for (int step = 0; step < steps; step++) {
            List<Long> active = new ArrayList<>();
            for (long id : model.live) {
                if (m.state(id) == TxnState.ACTIVE) {
                    active.add(id);
                }
            }
            int pick = rnd.nextInt(100);
            if (begun < TXNS && (pick < 8 || model.live.isEmpty())) {
                m.begin(begun);
                model.live.add((long) begun);
                begun++;
            } else if (pick < 70 && !active.isEmpty()) {
                long t = active.get(rnd.nextInt(active.size()));
                if (shrinking.contains(t)) {
                    continue;
                }
                String r = RESOURCES[rnd.nextInt(RESOURCES.length)];
                LockMode mode = LockMode.values()[rnd.nextInt(5)];
                Outcome o = m.acquire(t, r, mode);
                if (o.status() == Status.WAITING) {
                    model.blocked.add(t);
                    model.waits++;
                    assertEquals(TxnState.BLOCKED, m.state(t));
                } else if (o.status() == Status.GRANTED) {
                    assertEquals(TxnState.ACTIVE, m.state(t));
                    assertTrue(m.held(t, r).orElseThrow().covers(mode), "granted mode must cover request");
                } else {
                    assertEquals(TxnState.ABORTED, m.state(t));
                    assertTrue(o.events().aborted().contains(t));
                }
                model.apply(m, t, o.events());
            } else if (pick < 82 && !active.isEmpty()) {
                long t = active.get(rnd.nextInt(active.size()));
                model.apply(m, null, m.commit(t));
                model.live.remove(t);
                assertEquals(TxnState.COMMITTED, m.state(t));
            } else if (pick < 88 && !model.live.isEmpty()) {
                List<Long> live = new ArrayList<>(model.live);
                long t = live.get(rnd.nextInt(live.size()));
                model.blocked.remove(t);
                model.live.remove(t);
                model.apply(m, null, m.abort(t));
            } else if (!active.isEmpty()) {
                long t = active.get(rnd.nextInt(active.size()));
                Map<String, LockMode> held = m.locks(t);
                for (String r : held.keySet()) {
                    if (!hasHeldDescendant(held, r)) {
                        shrinking.add(t);
                        model.apply(m, null, m.unlock(t, r));
                        break;
                    }
                }
            }
            m.checkInvariants();
            model.compare(m, begun);
        }
        // drain: commit every ACTIVE txn until none is left; then nobody may still be blocked
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (long id : new ArrayList<>(model.live)) {
                if (m.state(id) == TxnState.ACTIVE) {
                    model.apply(m, null, m.commit(id));
                    model.live.remove(id);
                    progressed = true;
                    m.checkInvariants();
                    model.compare(m, begun);
                }
            }
        }
        assertEquals(Set.of(), model.blocked, "seed " + seed + ": blocked txns remain after all commits");
        assertEquals(Set.of(), model.live);
        for (String r : RESOURCES) {
            assertTrue(m.holders(r).isEmpty() && m.queue(r).isEmpty(), r);
        }
    }

    @Test
    void randomHierarchicalWorkloadsKeepEveryInvariant() {
        for (long seed = 1; seed <= 300; seed++) {
            runSeed(seed, 300);
        }
    }

    @Test
    void randomWorkloadsActuallyExerciseWaitsAndDeadlocks() {
        long waits = 0;
        long aborts = 0;
        for (long seed = 1; seed <= 60; seed++) {
            Random rnd = new Random(seed);
            LockManager m = new LockManager();
            Model model = new Model();
            for (int i = 0; i < TXNS; i++) {
                m.begin(i);
                model.live.add((long) i);
            }
            for (int step = 0; step < 150; step++) {
                List<Long> active = new ArrayList<>();
                for (long id : model.live) {
                    if (m.state(id) == TxnState.ACTIVE) {
                        active.add(id);
                    }
                }
                if (active.isEmpty()) {
                    break;
                }
                long t = active.get(rnd.nextInt(active.size()));
                if (rnd.nextInt(5) == 0) {
                    model.apply(m, null, m.commit(t));
                    model.live.remove(t);
                    continue;
                }
                LockMode mode = LockMode.S;
                if (rnd.nextBoolean()) {
                    mode = LockMode.X;
                }
                Outcome o = m.acquire(t, RESOURCES[3 + rnd.nextInt(5)], mode);
                if (o.status() == Status.WAITING) {
                    model.blocked.add(t);
                    waits++;
                }
                model.apply(m, t, o.events());
            }
            aborts += model.aborts;
        }
        assertTrue(waits > 100, "waits=" + waits);
        assertTrue(aborts > 20, "aborts=" + aborts);
    }

    // ---- flat oracle: one resource, each transaction requests once, S/X only ------------

    @Test
    void singleResourceMatchesANaiveFifoOracle() {
        for (long seed = 1; seed <= 200; seed++) {
            Random rnd = new Random(seed);
            LockManager m = new LockManager();
            List<long[]> holders = new ArrayList<>(); // {id, isX}
            List<long[]> queue = new ArrayList<>();
            int next = 0;
            for (int step = 0; step < 60; step++) {
                if (rnd.nextInt(3) > 0 || (holders.isEmpty() && queue.isEmpty())) {
                    long id = next++;
                    long x = rnd.nextInt(2);
                    m.begin(id);
                    Outcome o = m.acquire(id, "r", x == 1 ? LockMode.X : LockMode.S);
                    boolean grantable = queue.isEmpty() && compatible(holders, x);
                    if (grantable) {
                        holders.add(new long[] {id, x});
                        assertEquals(Status.GRANTED, o.status());
                    } else {
                        queue.add(new long[] {id, x});
                        assertEquals(Status.WAITING, o.status());
                    }
                } else if (!holders.isEmpty()) {
                    long[] h = holders.remove(rnd.nextInt(holders.size()));
                    Events e = m.commit(h[0]);
                    List<Long> woken = new ArrayList<>();
                    while (!queue.isEmpty() && compatible(holders, queue.get(0)[1])) {
                        long[] q = queue.remove(0);
                        holders.add(q);
                        woken.add(q[0]);
                    }
                    assertEquals(woken, e.unblocked(), "seed " + seed + " step " + step);
                }
                m.checkInvariants();
                List<Long> q = new ArrayList<>();
                for (long[] e : queue) {
                    q.add(e[0]);
                }
                assertEquals(q, m.queue("r"));
                assertEquals(holders.size(), m.holders("r").size());
            }
        }
    }

    private static boolean compatible(List<long[]> holders, long wantX) {
        for (long[] h : holders) {
            if (h[1] == 1 || wantX == 1) {
                return false;
            }
        }
        return true;
    }
}
