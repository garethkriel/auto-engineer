package ae.net;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResilientHttpTest {

    /** Scripted reply: status, body, optional Retry-After header, optional server-side delay. */
    record Reply(int status, String body, String retryAfter, long delayMs) {
        Reply(int status, String body) { this(status, body, null, 0); }
    }

    /** Sleeper that records requested durations instead of sleeping. */
    static class RecordingSleeper implements Sleeper {
        final List<Duration> slept = new ArrayList<>();
        @Override public void sleep(Duration d) { slept.add(d); }
    }

    HttpServer server;
    final List<Reply> script = new CopyOnWriteArrayList<>();
    final AtomicInteger hits = new AtomicInteger();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    HttpClient client;
    RecordingSleeper sleeper;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            int n = hits.getAndIncrement();
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply r = script.get(Math.min(n, script.size() - 1));
            try {
                if (r.delayMs() > 0) Thread.sleep(r.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (r.retryAfter() != null) ex.getResponseHeaders().add("Retry-After", r.retryAfter());
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        client = HttpClient.newHttpClient();
        sleeper = new RecordingSleeper();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x"); }

    RetryPolicy policy(int attempts) {
        return new RetryPolicy(attempts, Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofSeconds(30));
    }

    ResilientHttp http(RetryPolicy p) { return new ResilientHttp(client, p, sleeper); }

    HttpRequest.Builder get() { return HttpRequest.newBuilder(uri()).GET(); }

    @Test
    void successOnFirstTryDoesNotSleep() throws Exception {
        script.add(new Reply(200, "ok"));
        HttpResult r = http(policy(3)).send(get().build());
        assertEquals(200, r.status());
        assertEquals("ok", r.body());
        assertEquals(1, r.attempts());
        assertEquals(Duration.ZERO, r.totalDelay());
        assertEquals(List.of(), sleeper.slept);
    }

    @Test
    void retriesServerErrorsWithExponentialBackoff() throws Exception {
        script.add(new Reply(503, "a"));
        script.add(new Reply(502, "b"));
        script.add(new Reply(200, "done"));
        HttpResult r = http(policy(5)).send(get().build());
        assertEquals(200, r.status());
        assertEquals("done", r.body());
        assertEquals(3, r.attempts());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), sleeper.slept);
        assertEquals(Duration.ofMillis(300), r.totalDelay());
    }

    @Test
    void doesNotRetryClientErrors() throws Exception {
        script.add(new Reply(404, "nope"));
        HttpResult r = http(policy(5)).send(get().build());
        assertEquals(404, r.status());
        assertEquals(1, hits.get());
    }

    @Test
    void returnsLastResponseWhenAttemptsExhausted() throws Exception {
        script.add(new Reply(503, "still down"));
        HttpResult r = http(policy(3)).send(get().build());
        assertEquals(503, r.status());
        assertEquals("still down", r.body());
        assertEquals(3, r.attempts());
        assertEquals(3, hits.get());
        assertEquals(2, sleeper.slept.size());
    }

    @Test
    void retryAfterSecondsOverridesBackoff() throws Exception {
        script.add(new Reply(429, "slow", "1", 0));
        script.add(new Reply(200, "ok"));
        HttpResult r = http(policy(3)).send(get().build());
        assertEquals(200, r.status());
        assertEquals(List.of(Duration.ofSeconds(1)), sleeper.slept);
    }

    @Test
    void retryAfterIsCappedAtMaxDelay() throws Exception {
        script.add(new Reply(429, "slow", "3600", 0));
        script.add(new Reply(200, "ok"));
        http(policy(3)).send(get().build());
        assertEquals(List.of(Duration.ofSeconds(2)), sleeper.slept);
    }

    @Test
    void invalidRetryAfterFallsBackToBackoff() throws Exception {
        script.add(new Reply(503, "x", "soon", 0));
        script.add(new Reply(503, "x", "-5", 0));
        script.add(new Reply(503, "x", "Wed, 21 Oct 2026 07:28:00 GMT", 0));
        script.add(new Reply(200, "ok"));
        http(policy(5)).send(get().build());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400)), sleeper.slept);
    }

    @Test
    void retryAfterZeroMeansRetryImmediately() throws Exception {
        script.add(new Reply(503, "x", "0", 0));
        script.add(new Reply(200, "ok"));
        http(policy(3)).send(get().build());
        assertEquals(List.of(Duration.ZERO), sleeper.slept);
    }

    @Test
    void postWithoutIdempotencyKeyIsSentOnce() throws Exception {
        script.add(new Reply(503, "no"));
        HttpRequest req = HttpRequest.newBuilder(uri()).POST(BodyPublishers.ofString("payload")).build();
        HttpResult r = http(policy(5)).send(req);
        assertEquals(503, r.status());
        assertEquals(1, r.attempts());
        assertEquals(1, hits.get());
        assertEquals(List.of(), sleeper.slept);
    }

    @Test
    void postWithIdempotencyKeyRetriesAndResendsBody() throws Exception {
        script.add(new Reply(503, "no"));
        script.add(new Reply(200, "created"));
        HttpRequest req = HttpRequest.newBuilder(uri())
                .header("Idempotency-Key", "k-1")
                .POST(BodyPublishers.ofString("payload")).build();
        HttpResult r = http(policy(5)).send(req);
        assertEquals(200, r.status());
        assertEquals(List.of("payload", "payload"), bodies);
    }

    @Test
    void putAndDeleteAreRetried() throws Exception {
        script.add(new Reply(503, "no"));
        script.add(new Reply(200, "ok"));
        HttpRequest put = HttpRequest.newBuilder(uri()).PUT(BodyPublishers.ofString("v")).build();
        assertEquals(2, http(policy(3)).send(put).attempts());
        hits.set(0);
        HttpRequest del = HttpRequest.newBuilder(uri()).DELETE().build();
        assertEquals(2, http(policy(3)).send(del).attempts());
    }

    @Test
    void timeoutIsRetriedAsIoFailure() throws Exception {
        script.add(new Reply(200, "late", null, 600));
        script.add(new Reply(200, "fast"));
        HttpRequest req = get().timeout(Duration.ofMillis(150)).build();
        HttpResult r = http(policy(3)).send(req);
        assertEquals("fast", r.body());
        assertEquals(2, r.attempts());
    }

    @Test
    void connectionRefusedExhaustsAndThrowsLastWithSuppressed() throws Exception {
        int port;
        try (var s = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = s.getLocalPort();
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build();
        IOException e = assertThrows(IOException.class, () -> http(policy(3)).send(req));
        assertEquals(2, e.getSuppressed().length);
        assertEquals(2, sleeper.slept.size());
    }

    @Test
    void budgetStopsRetriesAndReturnsLastResponse() throws Exception {
        script.add(new Reply(503, "down"));
        RetryPolicy p = new RetryPolicy(10, Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(250));
        HttpResult r = http(p).send(get().build());
        // delays: 100 (total 100), 200 would make 300 > 250 -> stop
        assertEquals(503, r.status());
        assertEquals(2, r.attempts());
        assertEquals(List.of(Duration.ofMillis(100)), sleeper.slept);
    }

    @Test
    void budgetEqualToTotalIsAllowed() throws Exception {
        script.add(new Reply(503, "down"));
        script.add(new Reply(200, "ok"));
        RetryPolicy p = new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(100));
        assertEquals(200, http(p).send(get().build()).status());
    }

    @Test
    void interruptWhileSleepingRestoresFlag() {
        script.add(new Reply(503, "down"));
        Sleeper interrupting = d -> { throw new InterruptedException("stop"); };
        ResilientHttp h = new ResilientHttp(client, policy(3), interrupting);
        assertThrows(InterruptedException.class, () -> h.send(get().build()));
        assertTrue(Thread.interrupted(), "interrupt flag must be restored (and is cleared by this check)");
        assertEquals(1, hits.get());
    }

    @Test
    void nullRequestRejected() {
        assertThrows(NullPointerException.class, () -> http(policy(2)).send(null));
    }

    @Test
    void policyDelaySequenceAndCap() {
        RetryPolicy p = new RetryPolicy(10, Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(60));
        assertEquals(Duration.ofMillis(100), p.delayFor(1));
        assertEquals(Duration.ofMillis(200), p.delayFor(2));
        assertEquals(Duration.ofMillis(400), p.delayFor(3));
        assertEquals(Duration.ofMillis(500), p.delayFor(4));
        assertEquals(Duration.ofMillis(500), p.delayFor(200), "huge exponent must not overflow");
    }

    @Test
    void policyValidation() {
        Duration d = Duration.ofMillis(10);
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, d, d, d));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ZERO, d, d));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, d, Duration.ofMillis(5), d));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, d, d, Duration.ofMillis(-1)));
    }
}
