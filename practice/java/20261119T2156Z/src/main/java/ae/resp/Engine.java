package ae.resp;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Redis-like command engine: strings, lists, expiry, transactions and an append-only file.
 *
 * <p>All state changes are logged as deterministic commands (relative TTLs become absolute,
 * lazy expiry is logged as DEL) so that replaying the log with frozen time rebuilds the exact state.
 */
public final class Engine implements AutoCloseable {
    private static final String WRONGTYPE = "WRONGTYPE Operation against a key holding the wrong kind of value";
    private static final Resp.Value OK = new Resp.Simple("OK");
    private static final Resp.Value NIL = new Resp.Bulk(null);
    private static final long FROZEN = Long.MIN_VALUE;

    /** Per-connection state. */
    public static final class Session {
        private boolean multi;
        private boolean dirty;
        private final List<List<String>> queue = new ArrayList<>();
    }

    private record Arity(int min, int max) { }

    private static final Map<String, Arity> ARITY = new HashMap<>();

    private static void arity(String name, int min, int max) {
        ARITY.put(name, new Arity(min, max));
    }

    static {
        int inf = Integer.MAX_VALUE;
        arity("PING", 1, 2); arity("ECHO", 2, 2); arity("SET", 3, inf); arity("GET", 2, 2);
        arity("DEL", 2, inf); arity("EXISTS", 2, inf); arity("APPEND", 3, 3); arity("STRLEN", 2, 2);
        arity("INCR", 2, 2); arity("DECR", 2, 2); arity("INCRBY", 3, 3); arity("DECRBY", 3, 3);
        arity("MGET", 2, inf); arity("MSET", 3, inf); arity("TYPE", 2, 2);
        arity("EXPIRE", 3, 3); arity("PEXPIRE", 3, 3); arity("PEXPIREAT", 3, 3);
        arity("TTL", 2, 2); arity("PTTL", 2, 2); arity("PERSIST", 2, 2);
        arity("LPUSH", 3, inf); arity("RPUSH", 3, inf); arity("LPOP", 2, 2); arity("RPOP", 2, 2);
        arity("LLEN", 2, 2); arity("LRANGE", 4, 4); arity("KEYS", 2, 2);
        arity("DBSIZE", 1, 1); arity("FLUSHALL", 1, 1);
        arity("MULTI", 1, 1); arity("EXEC", 1, 1); arity("DISCARD", 1, 1);
    }

    private final Object lock = new Object();
    private final LongSupplier clock;
    private final Path aofPath;
    private final Map<String, Object> data = new HashMap<>();
    private final Map<String, Long> expiry = new HashMap<>();
    private FileOutputStream aof;

    public Engine(LongSupplier clockMillis, Path aofOrNull) throws IOException {
        this.clock = clockMillis;
        this.aofPath = aofOrNull;
        if (aofOrNull != null) {
            replay(aofOrNull);
            List<List<String>> log = new ArrayList<>();
            purge(clock.getAsLong(), log);
            aof = new FileOutputStream(aofOrNull.toFile(), true);
            append(log);
        }
    }

    public Session newSession() {
        return new Session();
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (aof != null) {
                aof.close();
                aof = null;
            }
        }
    }

    // ------------------------------------------------------------------ execution

    /** Executes one command for a session; never throws for bad input. */
    public Resp.Value execute(Session s, List<String> args) {
        if (args.isEmpty()) {
            return new Resp.Err("ERR empty command");
        }
        String name = args.get(0).toUpperCase(Locale.ROOT);
        synchronized (lock) {
            long now = clock.getAsLong();
            switch (name) {
                case "MULTI":
                    if (s.multi) {
                        return new Resp.Err("ERR MULTI calls can not be nested");
                    }
                    s.multi = true;
                    s.dirty = false;
                    s.queue.clear();
                    return OK;
                case "DISCARD":
                    if (!s.multi) {
                        return new Resp.Err("ERR DISCARD without MULTI");
                    }
                    reset(s);
                    return OK;
                case "EXEC":
                    return exec(s, now);
                default:
                    break;
            }
            Resp.Err bad = validate(name, args);
            if (s.multi) {
                if (bad != null) {
                    s.dirty = true;
                    return bad;
                }
                s.queue.add(args);
                return new Resp.Simple("QUEUED");
            }
            if (bad != null) {
                return bad;
            }
            List<List<String>> log = new ArrayList<>();
            Resp.Value r = dispatch(name, args, now, log);
            return withAppend(r, log);
        }
    }

    private static void reset(Session s) {
        s.multi = false;
        s.dirty = false;
        s.queue.clear();
    }

    private Resp.Value exec(Session s, long now) {
        if (!s.multi) {
            return new Resp.Err("ERR EXEC without MULTI");
        }
        boolean dirty = s.dirty;
        List<List<String>> queued = new ArrayList<>(s.queue);
        reset(s);
        if (dirty) {
            return new Resp.Err("EXECABORT Transaction discarded because of previous errors.");
        }
        List<List<String>> log = new ArrayList<>();
        List<Resp.Value> results = new ArrayList<>();
        for (List<String> cmd : queued) {
            results.add(dispatch(cmd.get(0).toUpperCase(Locale.ROOT), cmd, now, log));
        }
        return withAppend(new Resp.Arr(results), log);
    }

    private Resp.Value withAppend(Resp.Value result, List<List<String>> log) {
        try {
            append(log);
        } catch (IOException e) {
            return new Resp.Err("ERR AOF write failed: " + e.getMessage());
        }
        return result;
    }

    private static Resp.Err validate(String name, List<String> a) {
        Arity ar = ARITY.get(name);
        if (ar == null) {
            return new Resp.Err("ERR unknown command '" + name + "'");
        }
        boolean oddRequired = name.equals("MSET");
        if (a.size() < ar.min() || a.size() > ar.max() || (oddRequired && a.size() % 2 == 0)) {
            return new Resp.Err("ERR wrong number of arguments for '" + name.toLowerCase(Locale.ROOT) + "' command");
        }
        if (name.equals("SET")) {
            return parseSet(a, 0).error;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static Long toLong(String s) {
        if (!s.matches("-?[0-9]{1,19}")) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Resp.Err notInt() {
        return new Resp.Err("ERR value is not an integer or out of range");
    }

    private static final class SetOpts {
        Resp.Err error;
        boolean hasExpiry;
        long expireAt;
        boolean nx;
        boolean xx;
    }

    private static SetOpts parseSet(List<String> a, long now) {
        SetOpts o = new SetOpts();
        for (int i = 3; i < a.size(); i++) {
            String opt = a.get(i).toUpperCase(Locale.ROOT);
            switch (opt) {
                case "NX":
                    o.nx = true;
                    break;
                case "XX":
                    o.xx = true;
                    break;
                case "EX", "PX", "EXAT", "PXAT": {
                    if (o.hasExpiry || i + 1 >= a.size()) {
                        o.error = new Resp.Err("ERR syntax error");
                        return o;
                    }
                    Long n = toLong(a.get(++i));
                    if (n == null) {
                        o.error = notInt();
                        return o;
                    }
                    try {
                        boolean seconds = opt.startsWith("EX");
                        long ms = seconds ? Math.multiplyExact(n, 1000L) : n;
                        o.expireAt = opt.endsWith("AT") ? ms : Math.addExact(now, ms);
                    } catch (ArithmeticException e) {
                        o.error = new Resp.Err("ERR invalid expire time in 'set' command");
                        return o;
                    }
                    if (n <= 0) {
                        o.error = new Resp.Err("ERR invalid expire time in 'set' command");
                        return o;
                    }
                    o.hasExpiry = true;
                    break;
                }
                default:
                    o.error = new Resp.Err("ERR syntax error");
                    return o;
            }
        }
        if (o.nx && o.xx) {
            o.error = new Resp.Err("ERR syntax error");
        }
        return o;
    }

    /** Returns the live value of a key, lazily deleting (and logging) it when expired. */
    private Object live(String key, long now, List<List<String>> log) {
        Object v = data.get(key);
        if (v == null) {
            return null;
        }
        Long exp = expiry.get(key);
        if (exp != null && exp <= now) {
            data.remove(key);
            expiry.remove(key);
            log.add(List.of("DEL", key));
            return null;
        }
        return v;
    }

    private void purge(long now, List<List<String>> log) {
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Long> e : expiry.entrySet()) {
            if (e.getValue() <= now) {
                dead.add(e.getKey());
            }
        }
        dead.sort(null);
        for (String k : dead) {
            data.remove(k);
            expiry.remove(k);
            log.add(List.of("DEL", k));
        }
    }

    private static Resp.Err wrongType() {
        return new Resp.Err(WRONGTYPE);
    }

    // ------------------------------------------------------------------ commands

    @SuppressWarnings("unchecked")
    private Resp.Value dispatch(String name, List<String> a, long now, List<List<String>> log) {
        switch (name) {
            case "PING":
                return a.size() == 2 ? new Resp.Bulk(a.get(1)) : new Resp.Simple("PONG");
            case "ECHO":
                return new Resp.Bulk(a.get(1));
            case "SET": {
                SetOpts o = parseSet(a, now);
                String k = a.get(1);
                boolean exists = live(k, now, log) != null;
                if ((o.nx && exists) || (o.xx && !exists)) {
                    return NIL;
                }
                data.put(k, a.get(2));
                expiry.remove(k);
                List<String> entry = new ArrayList<>(List.of("SET", k, a.get(2)));
                if (o.hasExpiry) {
                    expiry.put(k, o.expireAt);
                    entry.add("PXAT");
                    entry.add(Long.toString(o.expireAt));
                }
                log.add(entry);
                return OK;
            }
            case "GET": {
                Object v = live(a.get(1), now, log);
                if (v == null) {
                    return NIL;
                }
                return v instanceof String str ? new Resp.Bulk(str) : wrongType();
            }
            case "MGET": {
                List<Resp.Value> out = new ArrayList<>();
                for (int i = 1; i < a.size(); i++) {
                    Object v = live(a.get(i), now, log);
                    if (v instanceof String str) {
                        out.add(new Resp.Bulk(str));
                    } else {
                        out.add(NIL);
                    }
                }
                return new Resp.Arr(out);
            }
            case "MSET": {
                List<String> entry = new ArrayList<>(a);
                for (int i = 1; i < a.size(); i += 2) {
                    data.put(a.get(i), a.get(i + 1));
                    expiry.remove(a.get(i));
                }
                log.add(entry);
                return OK;
            }
            case "DEL": {
                List<String> removed = new ArrayList<>();
                for (int i = 1; i < a.size(); i++) {
                    if (live(a.get(i), now, log) != null) {
                        data.remove(a.get(i));
                        expiry.remove(a.get(i));
                        removed.add(a.get(i));
                    }
                }
                if (!removed.isEmpty()) {
                    List<String> entry = new ArrayList<>();
                    entry.add("DEL");
                    entry.addAll(removed);
                    log.add(entry);
                }
                return new Resp.Int(removed.size());
            }
            case "EXISTS": {
                long n = 0;
                for (int i = 1; i < a.size(); i++) {
                    if (live(a.get(i), now, log) != null) {
                        n++;
                    }
                }
                return new Resp.Int(n);
            }
            case "TYPE": {
                Object v = live(a.get(1), now, log);
                if (v == null) {
                    return new Resp.Simple("none");
                }
                return new Resp.Simple(v instanceof String ? "string" : "list");
            }
            case "APPEND": {
                Object v = live(a.get(1), now, log);
                if (v != null && !(v instanceof String)) {
                    return wrongType();
                }
                String merged = (v == null ? "" : (String) v) + a.get(2);
                data.put(a.get(1), merged);
                log.add(List.of("APPEND", a.get(1), a.get(2)));
                return new Resp.Int(merged.getBytes(StandardCharsets.UTF_8).length);
            }
            case "STRLEN": {
                Object v = live(a.get(1), now, log);
                if (v == null) {
                    return new Resp.Int(0);
                }
                if (!(v instanceof String str)) {
                    return wrongType();
                }
                return new Resp.Int(str.getBytes(StandardCharsets.UTF_8).length);
            }
            case "INCR":
                return incr(a.get(1), 1, now, log);
            case "DECR":
                return incr(a.get(1), -1, now, log);
            case "INCRBY":
            case "DECRBY": {
                Long d = toLong(a.get(2));
                if (d == null) {
                    return notInt();
                }
                if (name.equals("DECRBY")) {
                    if (d == Long.MIN_VALUE) {
                        return new Resp.Err("ERR increment or decrement would overflow");
                    }
                    d = -d;
                }
                return incr(a.get(1), d, now, log);
            }
            case "EXPIRE":
            case "PEXPIRE":
            case "PEXPIREAT": {
                Long n = toLong(a.get(2));
                if (n == null) {
                    return notInt();
                }
                if (live(a.get(1), now, log) == null) {
                    return new Resp.Int(0);
                }
                long at;
                try {
                    long ms = name.equals("EXPIRE") ? Math.multiplyExact(n, 1000L) : n;
                    at = name.equals("PEXPIREAT") ? ms : Math.addExact(now, ms);
                } catch (ArithmeticException e) {
                    return new Resp.Err("ERR invalid expire time in '" + name.toLowerCase(Locale.ROOT) + "' command");
                }
                String k = a.get(1);
                if (at <= now) {
                    data.remove(k);
                    expiry.remove(k);
                    log.add(List.of("DEL", k));
                } else {
                    expiry.put(k, at);
                    log.add(List.of("PEXPIREAT", k, Long.toString(at)));
                }
                return new Resp.Int(1);
            }
            case "TTL":
            case "PTTL": {
                String k = a.get(1);
                if (live(k, now, log) == null) {
                    return new Resp.Int(-2);
                }
                Long exp = expiry.get(k);
                if (exp == null) {
                    return new Resp.Int(-1);
                }
                long ms = exp - now;
                return new Resp.Int(name.equals("PTTL") ? ms : (ms + 500) / 1000);
            }
            case "PERSIST": {
                String k = a.get(1);
                if (live(k, now, log) == null || expiry.remove(k) == null) {
                    return new Resp.Int(0);
                }
                log.add(List.of("PERSIST", k));
                return new Resp.Int(1);
            }
            case "LPUSH":
            case "RPUSH": {
                String k = a.get(1);
                Object v = live(k, now, log);
                if (v != null && !(v instanceof ArrayDeque)) {
                    return wrongType();
                }
                ArrayDeque<String> list = v == null ? new ArrayDeque<>() : (ArrayDeque<String>) v;
                for (int i = 2; i < a.size(); i++) {
                    if (name.equals("LPUSH")) {
                        list.addFirst(a.get(i));
                    } else {
                        list.addLast(a.get(i));
                    }
                }
                data.put(k, list);
                log.add(new ArrayList<>(a));
                return new Resp.Int(list.size());
            }
            case "LPOP":
            case "RPOP": {
                String k = a.get(1);
                Object v = live(k, now, log);
                if (v == null) {
                    return NIL;
                }
                if (!(v instanceof ArrayDeque)) {
                    return wrongType();
                }
                ArrayDeque<String> list = (ArrayDeque<String>) v;
                String item = name.equals("LPOP") ? list.pollFirst() : list.pollLast();
                if (list.isEmpty()) {
                    data.remove(k);
                    expiry.remove(k);
                }
                log.add(List.of(name, k));
                return new Resp.Bulk(item);
            }
            case "LLEN": {
                Object v = live(a.get(1), now, log);
                if (v == null) {
                    return new Resp.Int(0);
                }
                return v instanceof ArrayDeque<?> l ? new Resp.Int(l.size()) : wrongType();
            }
            case "LRANGE": {
                Long from = toLong(a.get(2));
                Long to = toLong(a.get(3));
                if (from == null || to == null) {
                    return notInt();
                }
                Object v = live(a.get(1), now, log);
                if (v == null) {
                    return new Resp.Arr(List.of());
                }
                if (!(v instanceof ArrayDeque)) {
                    return wrongType();
                }
                ArrayDeque<String> list = (ArrayDeque<String>) v;
                long len = list.size();
                long lo = from < 0 ? from + len : from;
                long hi = to < 0 ? to + len : to;
                lo = Math.max(lo, 0);
                hi = Math.min(hi, len - 1);
                List<Resp.Value> out = new ArrayList<>();
                long idx = 0;
                for (String item : list) {
                    if (idx >= lo && idx <= hi) {
                        out.add(new Resp.Bulk(item));
                    }
                    idx++;
                }
                return new Resp.Arr(out);
            }
            case "KEYS": {
                purge(now, log);
                List<Resp.Value> out = new ArrayList<>();
                for (String k : new TreeMap<>(data).keySet()) {
                    if (glob(a.get(1), k)) {
                        out.add(new Resp.Bulk(k));
                    }
                }
                return new Resp.Arr(out);
            }
            case "DBSIZE":
                purge(now, log);
                return new Resp.Int(data.size());
            case "FLUSHALL":
                data.clear();
                expiry.clear();
                log.add(List.of("FLUSHALL"));
                return OK;
            default:
                return new Resp.Err("ERR unknown command '" + name + "'");
        }
    }

    private Resp.Value incr(String k, long delta, long now, List<List<String>> log) {
        Object v = live(k, now, log);
        long cur = 0;
        if (v != null) {
            if (!(v instanceof String str)) {
                return wrongType();
            }
            Long parsed = toLong(str);
            if (parsed == null) {
                return notInt();
            }
            cur = parsed;
        }
        long next;
        try {
            next = Math.addExact(cur, delta);
        } catch (ArithmeticException e) {
            return new Resp.Err("ERR increment or decrement would overflow");
        }
        data.put(k, Long.toString(next));
        log.add(List.of("INCRBY", k, Long.toString(delta)));
        return new Resp.Int(next);
    }

    /** Glob with only {@code *} and {@code ?}. */
    static boolean glob(String pattern, String s) {
        int p = 0;
        int i = 0;
        int star = -1;
        int mark = 0;
        while (i < s.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = i;
            } else if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == s.charAt(i))) {
                p++;
                i++;
            } else if (star >= 0) {
                p = star + 1;
                i = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }

    // ------------------------------------------------------------------ AOF

    private void append(List<List<String>> log) throws IOException {
        if (aof == null || log.isEmpty()) {
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (List<String> cmd : log) {
            buf.writeBytes(Resp.encode(Resp.command(cmd)));
        }
        aof.write(buf.toByteArray());
        aof.flush();
    }

    /** Counts bytes consumed so the replay knows the offset of the last complete record. */
    private static final class Counting extends FilterInputStream {
        long count;

        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }

    private void replay(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        long size = Files.size(file);
        long good = 0;
        try (Counting in = new Counting(new BufferedInputStream(new FileInputStream(file.toFile())))) {
            while (true) {
                Resp.Value v;
                try {
                    v = Resp.parse(in);
                } catch (RespException e) {
                    if (e.truncated()) {
                        break;
                    }
                    throw new IOException("corrupt AOF at byte " + good + ": " + e.getMessage(), e);
                }
                if (v == null) {
                    break;
                }
                good = in.count;
                List<String> args = new ArrayList<>();
                if (!(v instanceof Resp.Arr arr) || arr.items() == null || arr.items().isEmpty()) {
                    throw new IOException("corrupt AOF: record ending at byte " + good + " is not a command");
                }
                for (Resp.Value item : arr.items()) {
                    if (!(item instanceof Resp.Bulk b) || b.text() == null) {
                        throw new IOException("corrupt AOF: non-bulk argument before byte " + good);
                    }
                    args.add(b.text());
                }
                String name = args.get(0).toUpperCase(Locale.ROOT);
                Resp.Err bad = validate(name, args);
                Resp.Value r = bad != null ? bad : dispatch(name, args, FROZEN, new ArrayList<>());
                if (r instanceof Resp.Err err) {
                    throw new IOException("corrupt AOF: record ending at byte " + good + " failed: " + err.message());
                }
            }
        }
        if (good < size) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(good);
            }
        }
    }

    /** Replaces the log with a compact image of the current state (temp file, then atomic move). */
    public void rewrite() throws IOException {
        synchronized (lock) {
            if (aofPath == null) {
                return;
            }
            purge(clock.getAsLong(), new ArrayList<>());
            List<List<String>> image = new ArrayList<>();
            for (String k : new TreeMap<>(data).keySet()) {
                Object v = data.get(k);
                if (v instanceof String str) {
                    image.add(List.of("SET", k, str));
                } else {
                    List<String> cmd = new ArrayList<>(List.of("RPUSH", k));
                    cmd.addAll((ArrayDeque<String>) v);
                    image.add(cmd);
                }
                Long exp = expiry.get(k);
                if (exp != null) {
                    image.add(List.of("PEXPIREAT", k, Long.toString(exp)));
                }
            }
            Path tmp = aofPath.resolveSibling(aofPath.getFileName() + ".tmp");
            try {
                try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                    for (List<String> cmd : image) {
                        out.write(Resp.encode(Resp.command(cmd)));
                    }
                    out.getFD().sync();
                }
                aof.close();
                Files.move(tmp, aofPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
                aof = new FileOutputStream(aofPath.toFile(), true);
            }
        }
    }
}
