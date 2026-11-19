package ae.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Differential test of Engine (with AOF and periodic crash/reopen) against a naive model. */
class ModelTest {
    @TempDir Path dir;

    /** Naive reference: strings and lists with absolute expiry. */
    static final class Model {
        final Map<String, String> str = new HashMap<>();
        final Map<String, ArrayList<String>> lists = new HashMap<>();
        final Map<String, Long> exp = new HashMap<>();
        long now;

        void sweep() {
            for (var it = exp.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                if (en.getValue() <= now) {
                    str.remove(en.getKey());
                    lists.remove(en.getKey());
                    it.remove();
                }
            }
        }
    }

    @Test
    void randomCommandsMatchModelAcrossRestarts() throws Exception {
        for (long seed = 1; seed <= 25; seed++) {
            Random rnd = new Random(seed);
            Model m = new Model();
            m.now = 10_000;
            Path aof = dir.resolve("s" + seed + ".aof");
            Engine e = new Engine(() -> m.now, aof);
            Engine.Session s = e.newSession();
            String[] keys = {"a", "b", "c", "d", "e"};
            int ops = 400;
            for (int i = 0; i < ops; i++) {
                String k = keys[rnd.nextInt(keys.length)];
                int op = rnd.nextInt(11);
                String ctx = "seed=" + seed + " op#" + i + " kind=" + op + " key=" + k;
                m.now += rnd.nextInt(4) == 0 ? rnd.nextInt(50) : 0;
                m.sweep();
                switch (op) {
                    case 0 -> {
                        String v = "v" + rnd.nextInt(100);
                        assertEquals(new Resp.Simple("OK"), e.execute(s, List.of("SET", k, v)), ctx);
                        m.lists.remove(k); m.exp.remove(k); m.str.put(k, v);
                    }
                    case 1 -> {
                        String v = "p" + rnd.nextInt(100);
                        long ttl = 1 + rnd.nextInt(80);
                        assertEquals(new Resp.Simple("OK"), e.execute(s, List.of("SET", k, v, "PX", "" + ttl)), ctx);
                        m.lists.remove(k); m.str.put(k, v); m.exp.put(k, m.now + ttl);
                    }
                    case 2 -> {
                        Resp.Value got = e.execute(s, List.of("GET", k));
                        if (m.lists.containsKey(k)) assertInstanceOf(Resp.Err.class, got, ctx);
                        else assertEquals(new Resp.Bulk(m.str.get(k)), got, ctx);
                    }
                    case 3 -> {
                        boolean had = m.str.containsKey(k) || m.lists.containsKey(k);
                        assertEquals(new Resp.Int(had ? 1 : 0), e.execute(s, List.of("DEL", k)), ctx);
                        m.str.remove(k); m.lists.remove(k); m.exp.remove(k);
                    }
                    case 4 -> {
                        String v = "e" + rnd.nextInt(10);
                        Resp.Value r = e.execute(s, List.of("RPUSH", k, v));
                        if (m.str.containsKey(k)) { assertInstanceOf(Resp.Err.class, r, ctx); break; }
                        m.lists.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
                        assertEquals(new Resp.Int(m.lists.get(k).size()), r, ctx);
                    }
                    case 5 -> {
                        Resp.Value r = e.execute(s, List.of("LPOP", k));
                        if (m.str.containsKey(k)) { assertInstanceOf(Resp.Err.class, r, ctx); break; }
                        ArrayList<String> l = m.lists.get(k);
                        if (l == null) { assertEquals(new Resp.Bulk(null), r, ctx); break; }
                        assertEquals(new Resp.Bulk(l.remove(0)), r, ctx);
                        if (l.isEmpty()) { m.lists.remove(k); m.exp.remove(k); }
                    }
                    case 6 -> {
                        long ttl = 1 + rnd.nextInt(60);
                        boolean had = m.str.containsKey(k) || m.lists.containsKey(k);
                        assertEquals(new Resp.Int(had ? 1 : 0), e.execute(s, List.of("PEXPIRE", k, "" + ttl)), ctx);
                        if (had) m.exp.put(k, m.now + ttl);
                    }
                    case 7 -> {
                        boolean had = m.str.containsKey(k) || m.lists.containsKey(k);
                        long expect = -2;
                        if (had) expect = -1;
                        if (m.exp.containsKey(k)) expect = m.exp.get(k) - m.now;
                        assertEquals(new Resp.Int(expect), e.execute(s, List.of("PTTL", k)), ctx);
                    }
                    case 8 -> {
                        Resp.Value r = e.execute(s, List.of("INCR", k));
                        if (m.lists.containsKey(k)) { assertInstanceOf(Resp.Err.class, r, ctx); break; }
                        String cur = m.str.get(k);
                        if (cur == null) { m.str.put(k, "1"); assertEquals(new Resp.Int(1), r, ctx); break; }
                        long n;
                        try { n = Long.parseLong(cur); } catch (NumberFormatException ex) {
                            assertInstanceOf(Resp.Err.class, r, ctx);
                            break;
                        }
                        m.str.put(k, "" + (n + 1));
                        assertEquals(new Resp.Int(n + 1), r, ctx);
                    }
                    case 9 -> {
                        List<String> expect = new ArrayList<>();
                        for (String key : new TreeSet<>(Arrays.asList(keys))) {
                            if (m.str.containsKey(key) || m.lists.containsKey(key)) expect.add(key);
                        }
                        List<Resp.Value> exp = new ArrayList<>();
                        for (String x : expect) exp.add(new Resp.Bulk(x));
                        assertEquals(new Resp.Arr(exp), e.execute(s, List.of("KEYS", "*")), ctx);
                    }
                    default -> {
                        // simulated crash: drop the engine without close, reopen from the log
                        e.close();
                        e = new Engine(() -> m.now, aof);
                        s = e.newSession();
                    }
                }
                if (i % 97 == 96) e.rewrite();
            }
            e.close();
        }
    }
}
