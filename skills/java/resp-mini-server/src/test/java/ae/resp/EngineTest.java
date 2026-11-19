package ae.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineTest {
    long now;
    Engine e;
    Engine.Session s;

    @BeforeEach
    void setUp() throws Exception {
        now = 1_000_000;
        e = new Engine(() -> now, null);
        s = e.newSession();
    }

    Resp.Value run(String... a) {
        return e.execute(s, Arrays.asList(a));
    }

    static Resp.Value bulk(String s) { return new Resp.Bulk(s); }
    static Resp.Value num(long n) { return new Resp.Int(n); }
    static final Resp.Value OK = new Resp.Simple("OK");

    static void assertErr(String prefix, Resp.Value v) {
        assertInstanceOf(Resp.Err.class, v, String.valueOf(v));
        assertTrue(((Resp.Err) v).message().startsWith(prefix), v.toString());
    }

    @Test
    void setGetDel() {
        assertEquals(OK, run("SET", "a", "1"));
        assertEquals(bulk("1"), run("get", "a"));
        assertEquals(bulk(null), run("GET", "zz"));
        assertEquals(num(1), run("DEL", "a", "zz"));
        assertEquals(num(0), run("EXISTS", "a"));
    }

    @Test
    void existsCountsDuplicates() {
        run("SET", "x", "1");
        assertEquals(num(2), run("EXISTS", "x", "x"));
    }

    @Test
    void nxXx() {
        assertEquals(bulk(null), run("SET", "k", "v", "XX"));
        assertEquals(OK, run("SET", "k", "v", "NX"));
        assertEquals(bulk(null), run("SET", "k", "w", "NX"));
        assertEquals(OK, run("SET", "k", "w", "XX"));
        assertEquals(bulk("w"), run("GET", "k"));
        assertErr("ERR syntax", run("SET", "k", "v", "NX", "XX"));
    }

    @Test
    void expiryAndTtl() {
        run("SET", "k", "v", "EX", "10");
        assertEquals(num(10_000), run("PTTL", "k"));
        assertEquals(num(10), run("TTL", "k"));
        now += 9_999;
        assertEquals(bulk("v"), run("GET", "k"));
        assertEquals(num(1), run("PTTL", "k"));
        now += 1;
        assertEquals(bulk(null), run("GET", "k"));
        assertEquals(num(-2), run("TTL", "k"));
        assertEquals(num(0), run("DBSIZE"));
    }

    @Test
    void setClearsExpiryButIncrAndAppendKeepIt() {
        run("SET", "k", "5", "PX", "500");
        run("INCR", "k");
        run("APPEND", "k", "0");
        assertEquals(num(500), run("PTTL", "k"));
        run("SET", "k", "1");
        assertEquals(num(-1), run("PTTL", "k"));
        assertEquals(num(-2), run("PTTL", "nope"));
    }

    @Test
    void expireAndPersist() {
        assertEquals(num(0), run("EXPIRE", "k", "5"));
        run("SET", "k", "v");
        assertEquals(num(1), run("PEXPIRE", "k", "300"));
        assertEquals(num(1), run("PERSIST", "k"));
        assertEquals(num(0), run("PERSIST", "k"));
        assertEquals(num(1), run("PEXPIREAT", "k", String.valueOf(now + 7)));
        assertEquals(num(7), run("PTTL", "k"));
        assertEquals(num(1), run("EXPIRE", "k", "-1"));
        assertEquals(bulk(null), run("GET", "k"));
    }

    @Test
    void invalidExpiries() {
        assertErr("ERR invalid expire", run("SET", "k", "v", "EX", "0"));
        assertErr("ERR value is not an integer", run("SET", "k", "v", "EX", "abc"));
        assertErr("ERR syntax", run("SET", "k", "v", "EX"));
        assertErr("ERR syntax", run("SET", "k", "v", "EX", "1", "PX", "5"));
    }

    @Test
    void incrFamily() {
        assertEquals(num(1), run("INCR", "n"));
        assertEquals(num(11), run("INCRBY", "n", "10"));
        assertEquals(num(-4), run("DECRBY", "n", "15"));
        assertEquals(num(-5), run("DECR", "n"));
        run("SET", "s", "abc");
        assertErr("ERR value is not an integer", run("INCR", "s"));
        run("SET", "m", String.valueOf(Long.MAX_VALUE));
        assertErr("ERR increment or decrement would overflow", run("INCR", "m"));
        assertEquals(bulk(String.valueOf(Long.MAX_VALUE)), run("GET", "m"));
        run("SET", "lo", String.valueOf(Long.MIN_VALUE));
        assertErr("ERR increment or decrement would overflow", run("DECR", "lo"));
        assertErr("ERR increment or decrement would overflow", run("DECRBY", "n", String.valueOf(Long.MIN_VALUE)));
    }

    @Test
    void appendStrlenMgetMset() {
        assertEquals(num(3), run("APPEND", "k", "hé"));
        assertEquals(num(3), run("STRLEN", "k"));
        assertEquals(OK, run("MSET", "a", "1", "b", "2"));
        assertEquals(new Resp.Arr(List.of(bulk("1"), bulk(null), bulk("2"))), run("MGET", "a", "zz", "b"));
        assertErr("ERR wrong number", run("MSET", "a", "1", "b"));
    }

    @Test
    void strlenCountsUtf8Bytes() {
        run("SET", "k", "hé");
        assertEquals(num(3), run("STRLEN", "k"));
    }

    @Test
    void listsAndWrongType() {
        assertEquals(num(3), run("RPUSH", "l", "a", "b", "c"));
        assertEquals(num(5), run("LPUSH", "l", "y", "x"));
        assertEquals(new Resp.Arr(List.of(bulk("x"), bulk("y"), bulk("a"), bulk("b"), bulk("c"))), run("LRANGE", "l", "0", "-1"));
        assertEquals(new Resp.Arr(List.of(bulk("b"), bulk("c"))), run("LRANGE", "l", "-2", "10"));
        assertEquals(new Resp.Arr(List.of()), run("LRANGE", "l", "3", "1"));
        assertEquals(new Resp.Arr(List.of()), run("LRANGE", "l", "-100", "-50"));
        assertEquals(bulk("x"), run("LPOP", "l"));
        assertEquals(bulk("c"), run("RPOP", "l"));
        assertEquals(num(3), run("LLEN", "l"));
        assertErr("WRONGTYPE", run("GET", "l"));
        run("SET", "s", "v");
        assertErr("WRONGTYPE", run("LPUSH", "s", "v"));
        assertErr("WRONGTYPE", run("APPEND", "l", "v"));
        assertErr("WRONGTYPE", run("INCR", "l"));
        assertEquals(new Resp.Simple("list"), run("TYPE", "l"));
        assertEquals(new Resp.Simple("string"), run("TYPE", "s"));
        assertEquals(new Resp.Simple("none"), run("TYPE", "q"));
        assertEquals(num(0), run("LLEN", "nokey"));
    }

    @Test
    void poppingLastElementDeletesKey() {
        run("RPUSH", "l", "a");
        run("PEXPIRE", "l", "100");
        assertEquals(bulk("a"), run("LPOP", "l"));
        assertEquals(new Resp.Simple("none"), run("TYPE", "l"));
        assertEquals(bulk(null), run("LPOP", "l"));
        run("SET", "l", "str");
        assertEquals(num(-1), run("PTTL", "l"), "stale expiry must not survive deletion");
    }

    @Test
    void keysAndGlob() {
        for (String k : new String[]{"hello", "hallo", "hxllo", "h", "world"}) run("SET", k, "1");
        assertEquals(new Resp.Arr(List.of(bulk("hallo"), bulk("hello"), bulk("hxllo"))), run("KEYS", "h?llo"));
        assertEquals(new Resp.Arr(List.of(bulk("h"), bulk("hallo"), bulk("hello"), bulk("hxllo"))), run("KEYS", "h*"));
        assertEquals(num(5), run("DBSIZE"));
        run("PEXPIRE", "h", "1");
        now += 1;
        assertEquals(num(4), run("DBSIZE"));
        assertEquals(OK, run("FLUSHALL"));
        assertEquals(num(0), run("DBSIZE"));
    }

    @Test
    void arityAndUnknown() {
        assertErr("ERR unknown command", run("NOPE"));
        assertErr("ERR wrong number", run("GET"));
        assertErr("ERR wrong number", run("GET", "a", "b"));
        assertErr("ERR wrong number", run("SET", "a"));
        assertErr("ERR", e.execute(s, List.of()));
        assertEquals(new Resp.Simple("PONG"), run("PING"));
        assertEquals(bulk("hi"), run("PING", "hi"));
        assertEquals(bulk("yo"), run("ECHO", "yo"));
    }

    @Test
    void transactionsApplyInOrder() {
        assertEquals(OK, run("MULTI"));
        assertEquals(new Resp.Simple("QUEUED"), run("SET", "a", "1"));
        assertEquals(new Resp.Simple("QUEUED"), run("INCR", "a"));
        Engine.Session other = e.newSession();
        assertEquals(bulk(null), e.execute(other, List.of("GET", "a")), "queued commands are invisible until EXEC");
        assertEquals(new Resp.Arr(List.of(OK, num(2))), run("EXEC"));
        assertEquals(bulk("2"), e.execute(other, List.of("GET", "a")));
        assertErr("ERR EXEC without MULTI", run("EXEC"));
    }

    @Test
    void runtimeErrorInsideExecDoesNotAbort() {
        run("RPUSH", "l", "x");
        run("MULTI");
        run("INCR", "l");
        run("SET", "b", "2");
        Resp.Value r = run("EXEC");
        Resp.Arr arr = assertInstanceOf(Resp.Arr.class, r);
        assertErr("WRONGTYPE", arr.items().get(0));
        assertEquals(OK, arr.items().get(1));
        assertEquals(bulk("2"), run("GET", "b"));
    }

    @Test
    void queueTimeSyntaxErrorAbortsExec() {
        run("MULTI");
        run("SET", "a", "1");
        assertErr("ERR wrong number", run("GET"));
        assertErr("EXECABORT", run("EXEC"));
        assertEquals(bulk(null), run("GET", "a"));
        assertEquals(OK, run("MULTI"), "session left MULTI after EXECABORT");
        assertEquals(new Resp.Arr(List.of()), run("EXEC"));
    }

    @Test
    void discardAndNesting() {
        assertErr("ERR DISCARD without MULTI", run("DISCARD"));
        run("MULTI");
        assertErr("ERR MULTI calls can not be nested", run("MULTI"));
        run("SET", "a", "1");
        assertEquals(OK, run("DISCARD"));
        assertEquals(bulk(null), run("GET", "a"));
    }

    @Test
    void expiryInsideTransactionUsesOneClockReading() {
        run("MULTI");
        run("SET", "k", "v", "PX", "5");
        run("PTTL", "k");
        Resp.Arr a = assertInstanceOf(Resp.Arr.class, run("EXEC"));
        assertEquals(num(5), a.items().get(1));
    }
}
