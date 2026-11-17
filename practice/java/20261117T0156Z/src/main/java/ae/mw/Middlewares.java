package ae.mw;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ready-made middlewares. */
public final class Middlewares {
    private Middlewares() { }

    /** Requires header {@code authorization: Bearer <token>}; otherwise 401 without calling next. */
    public static Middleware requireToken(String token) {
        Objects.requireNonNull(token, "token");
        String expected = "Bearer " + token;
        return (req, next) -> expected.equals(req.headers().get("authorization"))
                ? next.handle(req)
                : new Response(401, "unauthorized");
    }

    /** Converts RuntimeExceptions thrown downstream into a 500 whose body is the exception's simple name. */
    public static Middleware recover() {
        return (req, next) -> {
            try {
                return next.handle(req);
            } catch (RuntimeException e) {
                return new Response(500, e.getClass().getSimpleName());
            }
        };
    }

    /** Appends entry/exit lines to {@code sink}; rethrows downstream failures after logging them. */
    public static Middleware trace(List<String> sink) {
        Objects.requireNonNull(sink, "sink");
        return (req, next) -> {
            sink.add("-> " + req.method() + " " + req.path());
            try {
                Response res = next.handle(req);
                sink.add("<- " + res.status());
                return res;
            } catch (RuntimeException | Error e) {
                sink.add("<- error");
                throw e;
            }
        };
    }

    /** Caches 200 responses to GET requests per path for {@code ttl} (expired when age >= ttl). */
    public static Middleware cacheGet(Clock clock, Duration ttl) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        record Entry(Response response, Instant expires) { }
        Map<String, Entry> cache = new HashMap<>();
        return (req, next) -> {
            if (!req.method().equals("GET")) {
                return next.handle(req);
            }
            Instant now = clock.instant();
            synchronized (cache) {
                Entry e = cache.get(req.path());
                if (e != null && now.isBefore(e.expires())) {
                    return e.response();
                }
            }
            Response res = next.handle(req);
            if (res.status() == 200) {
                synchronized (cache) {
                    cache.put(req.path(), new Entry(res, now.plus(ttl)));
                }
            }
            return res;
        };
    }
}
