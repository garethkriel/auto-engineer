package ae.gc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Randomised comparison of {@link Heap} with a naive object-graph model. */
class HeapDifferentialTest {

    private static final class Model {
        final Map<Integer, int[]> data = new TreeMap<>();
        final Map<Integer, int[]> refs = new TreeMap<>();
        final Set<Integer> roots = new TreeSet<>();

        Set<Integer> reachable() {
            Set<Integer> seen = new TreeSet<>();
            Deque<Integer> work = new ArrayDeque<>(roots);
            while (!work.isEmpty()) {
                int h = work.poll();
                if (!seen.add(h)) continue;
                for (int r : refs.get(h)) if (r != 0) work.add(r);
            }
            return seen;
        }
    }

    private void compare(Heap heap, Model m) {
        heap.checkInvariants();
        // Heap may keep garbage until gc, so every model object must be live and data must match.
        for (int h : m.data.keySet()) {
            assertTrue(heap.isLive(h), "handle " + h);
            int[] d = m.data.get(h);
            int[] r = m.refs.get(h);
            for (int i = 0; i < d.length; i++) assertEquals(d[i], heap.getData(h, i), "data " + h + "[" + i + "]");
            for (int i = 0; i < r.length; i++) assertEquals(r[i], heap.getRef(h, i), "ref " + h + "[" + i + "]");
        }
        assertEquals(new ArrayList<>(m.data.keySet()), heap.liveHandles());
        // Blocks never overlap and used words equal the sum of block sizes
        long used = 0;
        for (int h : m.data.keySet()) used += 1 + m.data.get(h).length + m.refs.get(h).length;
        assertEquals(used, heap.stats().usedWords());
    }

    private void run(long seed, FitPolicy policy, boolean auto) {
        Random rnd = new Random(seed);
        Heap heap = new Heap(400, policy, auto);
        Model m = new Model();
        List<Integer> reclaimed = new ArrayList<>();
        heap.addFreeListener(reclaimed::addAll);
        for (int step = 0; step < 600; step++) {
            int op = rnd.nextInt(10);
            List<Integer> handles = new ArrayList<>(m.data.keySet());
            if (op <= 3) { // alloc
                int dw = rnd.nextInt(6);
                int rs = rnd.nextInt(4);
                int before = heap.stats().liveObjects();
                try {
                    int h = heap.alloc(dw, rs);
                    m.data.put(h, new int[dw]);
                    m.refs.put(h, new int[rs]);
                    if (rnd.nextInt(3) == 0) {
                        heap.addRoot(h);
                        m.roots.add(h);
                    }
                    if (auto) {
                        // an auto collection may have run before the alloc: drop unreachable model objects
                        Set<Integer> keep = m.reachable();
                        keep.add(h);
                        // the new handle may be unrooted; unreachable old objects are dropped only if heap did
                        for (Iterator<Integer> it = m.data.keySet().iterator(); it.hasNext(); ) {
                            int k = it.next();
                            if (!heap.isLive(k)) {
                                assertFalse(keep.contains(k), "reachable object reclaimed: " + k);
                                it.remove();
                                m.refs.remove(k);
                            }
                        }
                    }
                } catch (HeapFullException e) {
                    assertEquals(before, heap.stats().liveObjects() + reclaimedSinceLastCheck(reclaimed));
                    // reconcile model with whatever the failed alloc's gc reclaimed
                    for (Iterator<Integer> it = m.data.keySet().iterator(); it.hasNext(); ) {
                        int k = it.next();
                        if (!heap.isLive(k)) {
                            assertFalse(m.reachable().contains(k), "reachable object reclaimed: " + k);
                            it.remove();
                            m.refs.remove(k);
                            m.roots.remove(k);
                        }
                    }
                }
                reclaimed.clear();
            } else if (op <= 5 && !handles.isEmpty()) { // set data
                int h = handles.get(rnd.nextInt(handles.size()));
                int[] d = m.data.get(h);
                if (d.length > 0) {
                    int i = rnd.nextInt(d.length);
                    int v = rnd.nextInt();
                    d[i] = v;
                    heap.setData(h, i, v);
                }
            } else if (op <= 7 && !handles.isEmpty()) { // set ref
                int h = handles.get(rnd.nextInt(handles.size()));
                int[] r = m.refs.get(h);
                if (r.length > 0) {
                    int i = rnd.nextInt(r.length);
                    int t = rnd.nextInt(4) == 0 ? 0 : handles.get(rnd.nextInt(handles.size()));
                    r[i] = t;
                    heap.setRef(h, i, t);
                }
            } else if (op == 8 && !m.roots.isEmpty()) { // drop a root
                List<Integer> rs = new ArrayList<>(m.roots);
                int h = rs.get(rnd.nextInt(rs.size()));
                m.roots.remove(h);
                heap.removeRoot(h);
            } else if (op == 9) { // gc or compact
                if (rnd.nextBoolean()) {
                    Set<Integer> live = m.reachable();
                    int expectedFreed = m.data.size() - live.size();
                    long expectedWords = 0;
                    for (int h : m.data.keySet())
                        if (!live.contains(h)) expectedWords += 1 + m.data.get(h).length + m.refs.get(h).length;
                    reclaimed.clear();
                    Heap.GcResult r = heap.gc();
                    assertEquals(expectedFreed, r.freedObjects());
                    assertEquals(expectedWords, r.freedWords());
                    List<Integer> expectedIds = new ArrayList<>();
                    for (int h : m.data.keySet()) if (!live.contains(h)) expectedIds.add(h);
                    assertEquals(expectedIds, reclaimed);
                    reclaimed.clear();
                    m.data.keySet().retainAll(live);
                    m.refs.keySet().retainAll(live);
                } else {
                    heap.compact();
                    assertTrue(heap.stats().freeExtents() <= 1);
                }
            }
            compare(heap, m);
        }
    }

    private static int reclaimedSinceLastCheck(List<Integer> r) {
        return r.size();
    }

    @Test
    void firstFitManualCollection() {
        for (long seed = 1; seed <= 25; seed++) run(seed, FitPolicy.FIRST_FIT, false);
    }

    @Test
    void bestFitManualCollection() {
        for (long seed = 100; seed <= 125; seed++) run(seed, FitPolicy.BEST_FIT, false);
    }

    @Test
    void autoCollectBothPolicies() {
        for (long seed = 200; seed <= 220; seed++) {
            run(seed, FitPolicy.FIRST_FIT, true);
            run(seed, FitPolicy.BEST_FIT, true);
        }
    }
}
