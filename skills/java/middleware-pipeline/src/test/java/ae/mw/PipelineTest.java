package ae.mw;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PipelineTest {
    private static Request get(String path) {
        return new Request("get", path, Map.of());
    }

    @Test
    void requestNormalisesMethodAndHeaders() {
        Request r = new Request("post", "/a", Map.of("Authorization", "x"));
        assertEquals("POST", r.method());
        assertEquals(Map.of("authorization", "x"), r.headers());
        assertThrows(UnsupportedOperationException.class, () -> r.headers().put("a", "b"));
        assertEquals("1", r.withHeader("X-A", "1").headers().get("x-a"));
        assertFalse(r.headers().containsKey("x-a"));
    }

    @Test
    void responseRejectsBadStatus() {
        assertThrows(IllegalArgumentException.class, () -> new Response(99, ""));
        assertThrows(IllegalArgumentException.class, () -> new Response(600, ""));
        assertEquals(200, new Response(200, "ok").status());
    }

    @Test
    void firstUsedMiddlewareIsOutermost() {
        List<String> order = new ArrayList<>();
        Middleware a = (req, next) -> { order.add("a-in"); Response r = next.handle(req); order.add("a-out"); return r; };
        Middleware b = (req, next) -> { order.add("b-in"); Response r = next.handle(req); order.add("b-out"); return r; };
        Handler h = Pipeline.to(r -> { order.add("end"); return new Response(200, "x"); }).use(a).use(b).build();
        h.handle(get("/"));
        assertEquals(List.of("a-in", "b-in", "end", "b-out", "a-out"), order);
    }

    @Test
    void pipelineIsImmutable() {
        AtomicInteger calls = new AtomicInteger();
        Pipeline base = Pipeline.to(r -> new Response(200, "x"));
        Pipeline extended = base.use((req, next) -> { calls.incrementAndGet(); return next.handle(req); });
        base.build().handle(get("/"));
        assertEquals(0, calls.get());
        extended.build().handle(get("/"));
        assertEquals(1, calls.get());
    }

    @Test
    void middlewareCanShortCircuit() {
        AtomicInteger reached = new AtomicInteger();
        Handler h = Pipeline.to(r -> { reached.incrementAndGet(); return new Response(200, ""); })
                .use((req, next) -> new Response(418, "stop")).build();
        assertEquals(418, h.handle(get("/")).status());
        assertEquals(0, reached.get());
    }

    @Test
    void routerMatchesLiteralAndParams() {
        Router router = Router.empty()
                .route("GET", "/users/{id}/posts/{post}", r -> new Response(200, r.headers().get("x-param-id") + ":" + r.headers().get("x-param-post")))
                .route("GET", "/health", r -> new Response(200, "up"));
        assertEquals("7:9", router.handle(get("/users/7/posts/9")).body());
        assertEquals("up", router.handle(get("/health")).body());
        assertEquals(404, router.handle(get("/users/7")).status());
        assertEquals(404, router.handle(get("/health/x")).status());
    }

    @Test
    void routerReports405WithSortedAllowedMethods() {
        Router router = Router.empty()
                .route("POST", "/x", r -> new Response(201, ""))
                .route("DELETE", "/x", r -> new Response(204, ""))
                .route("GET", "/x", r -> new Response(200, ""));
        Response res = router.handle(new Request("PUT", "/x", Map.of()));
        assertEquals(405, res.status());
        assertEquals("DELETE,GET,POST", res.body());
        assertEquals(201, router.handle(new Request("post", "/x", Map.of())).status());
    }

    @Test
    void routerFirstMatchWinsAndTrailingSlashIgnored() {
        Router router = Router.empty()
                .route("GET", "/a/{x}", r -> new Response(200, "param"))
                .route("GET", "/a/lit", r -> new Response(200, "literal"));
        assertEquals("param", router.handle(get("/a/lit")).body());
        assertEquals("param", router.handle(get("/a/lit/")).body());
    }

    @Test
    void routerIsImmutable() {
        Router empty = Router.empty();
        empty.route("GET", "/a", r -> new Response(200, ""));
        assertEquals(404, empty.handle(get("/a")).status());
    }

    @Test
    void routerRejectsBadArguments() {
        Router r = Router.empty();
        assertThrows(IllegalArgumentException.class, () -> r.route("GET", " ", x -> null));
        assertThrows(NullPointerException.class, () -> r.route("GET", "/a", null));
        assertThrows(IllegalArgumentException.class, () -> r.route("GET", "no-slash", x -> null));
    }

    @Test
    void requireTokenRejectsWithoutCallingNext() {
        AtomicInteger reached = new AtomicInteger();
        Handler h = Pipeline.to(r -> { reached.incrementAndGet(); return new Response(200, "secret"); })
                .use(Middlewares.requireToken("t0k")).build();
        assertEquals(401, h.handle(get("/")).status());
        assertEquals(401, h.handle(new Request("GET", "/", Map.of("Authorization", "Bearer nope"))).status());
        assertEquals(401, h.handle(new Request("GET", "/", Map.of("Authorization", "t0k"))).status());
        assertEquals(0, reached.get());
        assertEquals("secret", h.handle(new Request("GET", "/", Map.of("Authorization", "Bearer t0k"))).body());
    }

    @Test
    void recoverTurnsExceptionsIntoFiveHundred() {
        Handler h = Pipeline.to(r -> { throw new IllegalStateException("boom"); }).use(Middlewares.recover()).build();
        Response res = h.handle(get("/"));
        assertEquals(500, res.status());
        assertEquals("IllegalStateException", res.body());
    }

    @Test
    void recoverOnlyCoversInnerMiddlewares() {
        Handler h = Pipeline.to(r -> new Response(200, ""))
                .use((req, next) -> { throw new IllegalArgumentException("outer"); })
                .use(Middlewares.recover()).build();
        assertThrows(IllegalArgumentException.class, () -> h.handle(get("/")));
    }

    @Test
    void traceRecordsEntryExitAndErrors() {
        List<String> sink = new ArrayList<>();
        Handler ok = Pipeline.to(r -> new Response(204, "")).use(Middlewares.trace(sink)).build();
        ok.handle(get("/p"));
        assertEquals(List.of("-> GET /p", "<- 204"), sink);
        sink.clear();
        Handler bad = Pipeline.to(r -> { throw new RuntimeException("x"); }).use(Middlewares.trace(sink)).build();
        assertThrows(RuntimeException.class, () -> bad.handle(get("/q")));
        assertEquals(List.of("-> GET /q", "<- error"), sink);
    }

    @Test
    void cacheServesRepeatedGetsUntilTtlExpires() {
        MutableClock clock = new MutableClock();
        AtomicInteger hits = new AtomicInteger();
        Handler h = Pipeline.to(r -> new Response(200, "v" + hits.incrementAndGet()))
                .use(Middlewares.cacheGet(clock, Duration.ofSeconds(10))).build();
        assertEquals("v1", h.handle(get("/a")).body());
        clock.advance(Duration.ofSeconds(9));
        assertEquals("v1", h.handle(get("/a")).body());
        assertEquals("v2", h.handle(get("/b")).body());
        clock.advance(Duration.ofSeconds(1)); // exactly ttl: expired
        assertEquals("v3", h.handle(get("/a")).body());
    }

    @Test
    void cacheSkipsNonGetAndNon200() {
        AtomicInteger hits = new AtomicInteger();
        Handler h = Pipeline.to(r -> hits.incrementAndGet() == 1 ? new Response(500, "e") : new Response(200, "ok"))
                .use(Middlewares.cacheGet(new MutableClock(), Duration.ofMinutes(1))).build();
        assertEquals(500, h.handle(get("/a")).status());
        assertEquals(200, h.handle(get("/a")).status()); // error not cached
        assertEquals(200, h.handle(get("/a")).status());
        assertEquals(2, hits.get());
        h.handle(new Request("POST", "/a", Map.of()));
        h.handle(new Request("POST", "/a", Map.of()));
        assertEquals(4, hits.get());
    }

    @Test
    void cacheRejectsBadTtl() {
        assertThrows(IllegalArgumentException.class, () -> Middlewares.cacheGet(Clock.systemUTC(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> Middlewares.cacheGet(Clock.systemUTC(), Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> Middlewares.cacheGet(null, Duration.ofSeconds(1)));
    }

    @Test
    void fullStackComposes() {
        List<String> sink = new ArrayList<>();
        Router router = Router.empty().route("GET", "/boom", r -> { throw new UnsupportedOperationException(); })
                .route("GET", "/hi/{name}", r -> new Response(200, "hi " + r.headers().get("x-param-name")));
        Handler h = Pipeline.to(router).use(Middlewares.requireToken("k")).use(Middlewares.recover())
                .use(Middlewares.trace(sink)).build();
        Map<String, String> auth = Map.of("authorization", "Bearer k");
        assertEquals("hi bob", h.handle(new Request("GET", "/hi/bob", auth)).body());
        assertEquals(500, h.handle(new Request("GET", "/boom", auth)).status());
        assertEquals(401, h.handle(get("/hi/bob")).status());
        assertEquals(List.of("-> GET /hi/bob", "<- 200", "-> GET /boom", "<- error"), sink);
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
