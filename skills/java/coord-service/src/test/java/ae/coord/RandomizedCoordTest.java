package ae.coord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Differential test: random operation streams versus a naive path-keyed model. */
class RandomizedCoordTest {

    /** Naive oracle. Mutations are applied to a copy so a failed multi leaves no trace. */
    static final class Model {
        record M(String data, int version, int cversion, long owner) {}

        TreeMap<String, M> nodes = new TreeMap<>();
        long zxid;

        Model() {
            nodes.put("/", new M("", 0, 0, 0));
        }

        static String parent(String p) {
            int i = p.lastIndexOf('/');
            return i == 0 ? "/" : p.substring(0, i);
        }

        List<String> children(String p) {
            List<String> out = new ArrayList<>();
            for (String k : nodes.keySet()) {
                if (!k.equals("/") && parent(k).equals(p)) {
                    out.add(k.substring(k.lastIndexOf('/') + 1));
                }
            }
            return out;
        }

        /** Returns resulting path or the failing code. */
        Object apply(Op op, long session) {
            if (op instanceof Op.Create c) {
                String par = parent(c.path());
                M pm = nodes.get(par);
                if (pm == null) return CoordException.Code.NO_NODE;
                if (pm.owner() != 0) return CoordException.Code.EPHEMERAL_PARENT;
                String actual = c.mode().sequential() ? c.path() + String.format("%010d", pm.cversion()) : c.path();
                if (nodes.containsKey(actual)) return CoordException.Code.NODE_EXISTS;
                nodes.put(actual, new M(c.data(), 0, 0, c.mode().ephemeral() ? session : 0));
                nodes.put(par, new M(pm.data(), pm.version(), pm.cversion() + 1, pm.owner()));
                return actual;
            } else if (op instanceof Op.Delete d) {
                M m = nodes.get(d.path());
                if (m == null) return CoordException.Code.NO_NODE;
                if (d.expectedVersion() != -1 && d.expectedVersion() != m.version()) return CoordException.Code.BAD_VERSION;
                if (!children(d.path()).isEmpty()) return CoordException.Code.NOT_EMPTY;
                nodes.remove(d.path());
                M pm = nodes.get(parent(d.path()));
                nodes.put(parent(d.path()), new M(pm.data(), pm.version(), pm.cversion() + 1, pm.owner()));
                return d.path();
            } else if (op instanceof Op.SetData s) {
                M m = nodes.get(s.path());
                if (m == null) return CoordException.Code.NO_NODE;
                if (s.expectedVersion() != -1 && s.expectedVersion() != m.version()) return CoordException.Code.BAD_VERSION;
                nodes.put(s.path(), new M(s.data(), m.version() + 1, m.cversion(), m.owner()));
                return s.path();
            } else {
                Op.Check k = (Op.Check) op;
                M m = nodes.get(k.path());
                if (m == null) return CoordException.Code.NO_NODE;
                if (k.expectedVersion() != -1 && k.expectedVersion() != m.version()) return CoordException.Code.BAD_VERSION;
                return k.path();
            }
        }

        /** Atomic: returns paths, or {code, index} on failure. */
        Object multi(List<Op> ops, long session) {
            TreeMap<String, M> backup = new TreeMap<>(nodes);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < ops.size(); i++) {
                Object r = apply(ops.get(i), session);
                if (r instanceof CoordException.Code code) {
                    nodes = backup;
                    return new Object[] {code, i};
                }
                out.add((String) r);
            }
            if (!ops.isEmpty()) zxid++;
            return out;
        }

        void expire(long session) {
            List<String> mine = new ArrayList<>();
            for (var e : nodes.entrySet()) {
                if (e.getValue().owner() == session) mine.add(e.getKey());
            }
            for (String p : mine) {
                nodes.remove(p);
                M pm = nodes.get(parent(p));
                nodes.put(parent(p), new M(pm.data(), pm.version(), pm.cversion() + 1, pm.owner()));
            }
            if (!mine.isEmpty()) zxid++;
        }
    }

    @Test
    void randomStreamsMatchTheModelAndKeepInvariants() {
        for (long seed = 1; seed <= 25; seed++) {
            run(seed);
        }
    }

    private void run(long seed) {
        Random rnd = new Random(seed);
        ManualClock clock = new ManualClock();
        CoordService svc = new CoordService(clock);
        Model model = new Model();
        Map<Long, Long> lastBeat = new HashMap<>();
        Map<Long, Long> timeout = new HashMap<>();
        List<Long> live = new ArrayList<>();
        String[] names = {"a", "b", "c", "d"};

        for (int i = 0; i < 3; i++) {
            long t = 50 + rnd.nextInt(200);
            long s = svc.connect(t);
            live.add(s);
            timeout.put(s, t);
            lastBeat.put(s, 0L);
        }
        for (int step = 0; step < 400; step++) {
            String ctx = "seed=" + seed + " step=" + step;
            // 1. time passes; model expires sessions the same way
            if (rnd.nextInt(5) == 0) {
                clock.advance(rnd.nextInt(60));
            }
            long now = clock.now();
            List<Long> due = new ArrayList<>();
            for (long s : live) {
                if (now - lastBeat.get(s) >= timeout.get(s)) due.add(s);
            }
            due.sort(java.util.Comparator.<Long>comparingLong(s -> lastBeat.get(s) + timeout.get(s))
                    .thenComparingLong(s -> s));
            for (long s : due) {
                model.expire(s);
                live.remove(s);
            }
            if (live.size() < 2) {
                long t = 50 + rnd.nextInt(200);
                long s = svc.connect(t);
                live.add(s);
                timeout.put(s, t);
                lastBeat.put(s, clock.now());
            }
            if (rnd.nextInt(8) == 0) {
                long s = live.get(rnd.nextInt(live.size()));
                svc.heartbeat(s);
                lastBeat.put(s, clock.now());
            }
            // 2. random op or multi from a live session
            long s = live.get(rnd.nextInt(live.size()));
            int n = rnd.nextInt(4) == 0 ? 2 + rnd.nextInt(3) : 1;
            List<Op> ops = new ArrayList<>();
            for (int k = 0; k < n; k++) ops.add(randomOp(rnd, model, names));
            Object expected = model.multi(ops, s);
            svc.tick(); // lazy expiry (already mirrored in the model) must not count towards the failed multi
            long zBefore = svc.lastZxid();
            try {
                List<String> got = svc.multi(s, ops);
                assertTrue(expected instanceof List, ctx + " expected failure " + render(expected) + " ops=" + ops);
                assertEquals(expected, got, ctx);
            } catch (CoordException e) {
                assertTrue(expected instanceof Object[], ctx + " unexpected failure " + e + " ops=" + ops);
                Object[] ex = (Object[]) expected;
                assertEquals(ex[0], e.code(), ctx + " ops=" + ops);
                assertEquals(ex[1], e.opIndex(), ctx);
                assertEquals(zBefore, svc.lastZxid(), ctx + " failed multi consumed a zxid");
            }
            // 3. full state comparison + invariants (also drives lazy expiry in the service first)
            svc.checkInvariants();
            for (var e : model.nodes.entrySet()) {
                Stat st = svc.exists(s, e.getKey(), null).orElseThrow(() -> new AssertionError(ctx + " missing " + e.getKey()));
                assertEquals(e.getValue().version(), st.version(), ctx + " " + e.getKey());
                assertEquals(e.getValue().cversion(), st.cversion(), ctx + " " + e.getKey());
                assertEquals(e.getValue().owner(), st.ephemeralOwner(), ctx + " " + e.getKey());
                assertEquals(e.getValue().data(), svc.getData(s, e.getKey(), null).data(), ctx);
                assertEquals(model.children(e.getKey()), svc.getChildren(s, e.getKey(), null), ctx + " " + e.getKey());
            }
            assertEquals(model.zxid, svc.lastZxid(), ctx);
        }
    }

    private static String render(Object o) {
        return o instanceof Object[] a ? java.util.Arrays.toString(a) : String.valueOf(o);
    }

    private static Op randomOp(Random rnd, Model model, String[] names) {
        List<String> existing = new ArrayList<>(model.nodes.keySet());
        String base = rnd.nextInt(10) == 0 ? "/zz" : existing.get(rnd.nextInt(existing.size()));
        String target = existing.get(rnd.nextInt(existing.size()));
        int ver = rnd.nextInt(3) == 0 ? rnd.nextInt(3) : -1;
        switch (rnd.nextInt(5)) {
            case 0, 1: {
                CreateMode mode = CreateMode.values()[rnd.nextInt(4)];
                String name = names[rnd.nextInt(names.length)] + (mode.sequential() ? "-" : "");
                return new Op.Create((base.equals("/") ? "" : base) + "/" + name, "d" + rnd.nextInt(100), mode);
            }
            case 2:
                return target.equals("/") ? new Op.Check("/", -1) : new Op.Delete(target, ver);
            case 3:
                return new Op.SetData(target, "v" + rnd.nextInt(100), ver);
            default:
                return new Op.Check(target, ver);
        }
    }
}
