package ae.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AofTest {
    @TempDir Path dir;
    long now = 5_000;

    Engine open() throws IOException { return new Engine(() -> now, dir.resolve("a.aof")); }

    static Resp.Value run(Engine e, Engine.Session s, String... a) { return e.execute(s, Arrays.asList(a)); }

    @Test
    void replayRestoresStateAndRelativeTtlBecomesAbsolute() throws Exception {
        Engine e = open();
        Engine.Session s = e.newSession();
        run(e, s, "SET", "a", "1");
        run(e, s, "SET", "t", "v", "EX", "10");
        run(e, s, "RPUSH", "l", "x", "y");
        run(e, s, "INCR", "n");
        run(e, s, "EXPIRE", "a", "20");
        run(e, s, "DEL", "zz");
        e.close();
        now += 12_000;
        Engine r = open();
        Engine.Session rs = r.newSession();
        assertEquals(new Resp.Bulk(null), run(r, rs, "GET", "t"), "t expired at 15000 absolute");
        assertEquals(new Resp.Bulk("1"), run(r, rs, "GET", "a"));
        assertEquals(new Resp.Int(8_000), run(r, rs, "PTTL", "a"));
        assertEquals(new Resp.Int(2), run(r, rs, "LLEN", "l"));
        assertEquals(new Resp.Bulk("1"), run(r, rs, "GET", "n"));
        r.close();
    }

    @Test
    void readsAndFailedWritesAreNotLogged() throws Exception {
        Engine e = open();
        Engine.Session s = e.newSession();
        run(e, s, "GET", "a");
        run(e, s, "PING");
        run(e, s, "SET", "a", "v", "NX");
        long len = Files.size(dir.resolve("a.aof"));
        run(e, s, "SET", "a", "w", "NX");
        run(e, s, "INCR", "a");
        run(e, s, "DEL", "missing");
        run(e, s, "LPUSH", "a", "x");
        run(e, s, "PERSIST", "a");
        run(e, s, "EXPIRE", "missing", "5");
        assertEquals(len, Files.size(dir.resolve("a.aof")), "no-op or failed writes must not grow the log");
        e.close();
    }

    @Test
    void transactionLoggedOnlyOnExecAndAtomically() throws Exception {
        Engine e = open();
        Engine.Session s = e.newSession();
        run(e, s, "MULTI");
        run(e, s, "SET", "a", "1");
        run(e, s, "RPUSH", "l", "z");
        assertEquals(0, Files.size(dir.resolve("a.aof")));
        run(e, s, "EXEC");
        e.close();
        Engine r = open();
        assertEquals(new Resp.Bulk("1"), run(r, r.newSession(), "GET", "a"));
        assertEquals(new Resp.Int(1), run(r, r.newSession(), "LLEN", "l"));
        r.close();
    }

    @Test
    void truncatedTailIsDroppedAndFileRepaired() throws Exception {
        Engine e = open();
        Engine.Session s = e.newSession();
        run(e, s, "SET", "a", "1");
        run(e, s, "SET", "b", "2");
        e.close();
        Path f = dir.resolve("a.aof");
        byte[] all = Files.readAllBytes(f);
        int firstEnd = Resp.encode(Resp.command("SET", "a", "1")).length;
        for (int cut = firstEnd; cut < all.length; cut++) {
            Files.write(f, Arrays.copyOf(all, cut));
            Engine r = open();
            Engine.Session rs = r.newSession();
            assertEquals(new Resp.Bulk("1"), run(r, rs, "GET", "a"), "cut=" + cut);
            assertEquals(new Resp.Bulk(null), run(r, rs, "GET", "b"), "cut=" + cut);
            assertEquals(firstEnd, Files.size(f), "cut=" + cut + " tail must be truncated away");
            run(r, rs, "SET", "c", "3");
            r.close();
            Engine again = open();
            assertEquals(new Resp.Bulk("3"), run(again, again.newSession(), "GET", "c"), "append after repair, cut=" + cut);
            again.close();
        }
    }

    @Test
    void garbageInTheMiddleIsAnError() throws Exception {
        Engine e = open();
        run(e, e.newSession(), "SET", "a", "1");
        e.close();
        Path f = dir.resolve("a.aof");
        byte[] good = Files.readAllBytes(f);
        byte[] bad = new byte[good.length + 4 + good.length];
        System.arraycopy(good, 0, bad, 0, good.length);
        System.arraycopy("?x\r\n".getBytes(), 0, bad, good.length, 4);
        System.arraycopy(good, 0, bad, good.length + 4, good.length);
        Files.write(f, bad);
        assertThrows(IOException.class, this::open);
    }

    @Test
    void unknownCommandInLogIsAnError() throws Exception {
        Files.write(dir.resolve("a.aof"), Resp.encode(Resp.command("BOGUS", "x")));
        assertThrows(IOException.class, this::open);
    }

    @Test
    void rewriteShrinksLogAndPreservesState() throws Exception {
        Engine e = open();
        Engine.Session s = e.newSession();
        for (int i = 0; i < 200; i++) run(e, s, "INCR", "counter");
        run(e, s, "SET", "gone", "x", "PX", "100");
        run(e, s, "SET", "keep", "x", "PX", "100000");
        run(e, s, "RPUSH", "l", "a", "b", "c");
        run(e, s, "LPOP", "l");
        now += 500;
        long before = Files.size(dir.resolve("a.aof"));
        e.rewrite();
        long after = Files.size(dir.resolve("a.aof"));
        assertTrue(after < before / 10, before + " -> " + after);
        assertEquals(new Resp.Bulk("200"), run(e, s, "GET", "counter"));
        run(e, s, "SET", "post", "1");
        e.close();
        assertFalse(Files.exists(dir.resolve("a.aof.tmp")), "temp file must not linger");
        Engine r = open();
        Engine.Session rs = r.newSession();
        assertEquals(new Resp.Bulk("200"), run(r, rs, "GET", "counter"));
        assertEquals(new Resp.Bulk(null), run(r, rs, "GET", "gone"));
        assertEquals(new Resp.Int(99_500), run(r, rs, "PTTL", "keep"));
        assertEquals(new Resp.Arr(java.util.List.of(new Resp.Bulk("b"), new Resp.Bulk("c"))), run(r, rs, "LRANGE", "l", "0", "-1"));
        assertEquals(new Resp.Bulk("1"), run(r, rs, "GET", "post"));
        r.close();
    }

    @Test
    void rewriteWithoutAofIsNoOp() throws Exception {
        Engine e = new Engine(() -> now, null);
        assertDoesNotThrow(e::rewrite);
    }

    @Test
    void flushAllIsLogged() throws Exception {
        Engine e = open();
        Engine.Session s = e.newSession();
        run(e, s, "SET", "a", "1");
        run(e, s, "FLUSHALL");
        run(e, s, "SET", "b", "2");
        e.close();
        Engine r = open();
        assertEquals(new Resp.Int(1), run(r, r.newSession(), "DBSIZE"));
        r.close();
    }
}
