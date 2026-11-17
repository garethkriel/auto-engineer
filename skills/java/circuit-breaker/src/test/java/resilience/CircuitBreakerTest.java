package resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final CircuitBreaker.Clock clock = now::get;

    private CircuitBreaker breaker(int threshold, long open, int successes) {
        return new CircuitBreaker(threshold, open, successes, t -> true, clock);
    }

    private static void fail(CircuitBreaker b) {
        assertThrows(IllegalStateException.class, () -> b.run(() -> { throw new IllegalStateException("x"); }));
    }

    @Test
    void closedCallReturnsValue() {
        assertEquals("ok", breaker(2, 100, 1).call(() -> "ok"));
    }

    @Test
    void opensAfterThresholdConsecutiveFailures() {
        CircuitBreaker b = breaker(3, 100, 1);
        fail(b);
        fail(b);
        assertEquals(CircuitBreaker.State.CLOSED, b.state());
        assertEquals(2, b.consecutiveFailures());
        fail(b);
        assertEquals(CircuitBreaker.State.OPEN, b.state());
    }

    @Test
    void successResetsFailureStreak() {
        CircuitBreaker b = breaker(3, 100, 1);
        fail(b);
        fail(b);
        b.call(() -> 1);
        assertEquals(0, b.consecutiveFailures());
        fail(b);
        fail(b);
        assertEquals(CircuitBreaker.State.CLOSED, b.state());
    }

    @Test
    void openRejectsWithoutRunningAction() {
        CircuitBreaker b = breaker(1, 100, 1);
        fail(b);
        AtomicInteger ran = new AtomicInteger();
        assertThrows(CircuitOpenException.class, () -> b.call(ran::incrementAndGet));
        assertEquals(0, ran.get());
        assertEquals(1, b.rejectedCount());
    }

    @Test
    void stillOpenOneMillisBeforeDeadline() {
        CircuitBreaker b = breaker(1, 100, 1);
        fail(b);
        now.addAndGet(99);
        assertThrows(CircuitOpenException.class, () -> b.call(() -> 1));
        assertEquals(CircuitBreaker.State.OPEN, b.state());
    }

    @Test
    void probeAllowedExactlyAtDeadlineAndClosesOnSuccess() {
        CircuitBreaker b = breaker(1, 100, 1);
        fail(b);
        now.addAndGet(100);
        assertEquals("p", b.call(() -> "p"));
        assertEquals(CircuitBreaker.State.CLOSED, b.state());
        assertEquals(0, b.consecutiveFailures());
    }

    @Test
    void failedProbeReopensWithFreshTimer() {
        CircuitBreaker b = breaker(1, 100, 1);
        fail(b);
        now.addAndGet(100);
        fail(b); // probe fails
        assertEquals(CircuitBreaker.State.OPEN, b.state());
        now.addAndGet(99);
        assertThrows(CircuitOpenException.class, () -> b.call(() -> 1));
        now.addAndGet(1);
        assertEquals(1, b.call(() -> 1));
    }

    @Test
    void needsSeveralProbeSuccessesToClose() {
        CircuitBreaker b = breaker(1, 100, 3);
        fail(b);
        now.addAndGet(100);
        b.call(() -> 1);
        assertEquals(CircuitBreaker.State.HALF_OPEN, b.state());
        b.call(() -> 1);
        assertEquals(CircuitBreaker.State.HALF_OPEN, b.state());
        b.call(() -> 1);
        assertEquals(CircuitBreaker.State.CLOSED, b.state());
    }

    @Test
    void failureAfterPartialProbeSuccessReopens() {
        CircuitBreaker b = breaker(1, 100, 2);
        fail(b);
        now.addAndGet(100);
        b.call(() -> 1);
        fail(b);
        assertEquals(CircuitBreaker.State.OPEN, b.state());
        now.addAndGet(100);
        b.call(() -> 1);
        // success count restarted: one success is not enough
        assertEquals(CircuitBreaker.State.HALF_OPEN, b.state());
    }

    @Test
    void neutralThrowableDoesNotCountOrReset() {
        CircuitBreaker b = new CircuitBreaker(2, 100, 1, t -> !(t instanceof IllegalArgumentException), clock);
        assertThrows(IllegalStateException.class, () -> b.run(() -> { throw new IllegalStateException(); }));
        assertThrows(IllegalArgumentException.class, () -> b.run(() -> { throw new IllegalArgumentException(); }));
        assertEquals(1, b.consecutiveFailures());
        assertThrows(IllegalStateException.class, () -> b.run(() -> { throw new IllegalStateException(); }));
        assertEquals(CircuitBreaker.State.OPEN, b.state());
    }

    @Test
    void neutralThrowableInProbeReleasesProbeSlot() {
        CircuitBreaker b = new CircuitBreaker(1, 100, 1, t -> !(t instanceof IllegalArgumentException), clock);
        assertThrows(IllegalStateException.class, () -> b.run(() -> { throw new IllegalStateException(); }));
        now.addAndGet(100);
        assertThrows(IllegalArgumentException.class, () -> b.run(() -> { throw new IllegalArgumentException(); }));
        assertEquals(CircuitBreaker.State.HALF_OPEN, b.state());
        assertEquals(5, b.call(() -> 5)); // slot free again
        assertEquals(CircuitBreaker.State.CLOSED, b.state());
    }

    @Test
    void errorsAreCountedAndRethrown() {
        CircuitBreaker b = breaker(1, 100, 1);
        assertThrows(StackOverflowError.class, () -> b.run(() -> { throw new StackOverflowError(); }));
        assertEquals(CircuitBreaker.State.OPEN, b.state());
    }

    @Test
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(1, 1, 0));
        assertThrows(NullPointerException.class, () -> new CircuitBreaker(1, 1, 1, null, clock));
        assertThrows(NullPointerException.class, () -> new CircuitBreaker(1, 1, 1, t -> true, null));
        assertThrows(NullPointerException.class, () -> breaker(1, 1, 1).call(null));
    }

    @Test
    void concurrentCallerRejectedWhileProbeInFlight() throws Exception {
        CircuitBreaker b = breaker(1, 100, 1);
        fail(b);
        now.addAndGet(100);
        CountDownLatch inProbe = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<String> probe = ex.submit(() -> b.call(() -> {
                inProbe.countDown();
                await(release);
                return "probe";
            }));
            assertTrue(inProbe.await(5, TimeUnit.SECONDS));
            assertEquals(CircuitBreaker.State.HALF_OPEN, b.state());
            assertThrows(CircuitOpenException.class, () -> b.call(() -> "second"));
            release.countDown();
            assertEquals("probe", probe.get(5, TimeUnit.SECONDS));
            assertEquals(CircuitBreaker.State.CLOSED, b.state());
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    void staleFailureFromEarlierGenerationIsIgnored() throws Exception {
        CircuitBreaker b = breaker(2, 100, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            // slow call begins while CLOSED
            Future<?> slow = ex.submit(() -> b.run(() -> {
                started.countDown();
                await(release);
                throw new IllegalStateException("late");
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            fail(b);
            fail(b); // opens
            now.addAndGet(100);
            b.call(() -> 1); // probe succeeds, closes (new generation)
            assertEquals(CircuitBreaker.State.CLOSED, b.state());
            release.countDown();
            ExecutionException e = assertThrows(ExecutionException.class, () -> slow.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertEquals(0, b.consecutiveFailures(), "late failure must not count against the new generation");
            assertEquals(CircuitBreaker.State.CLOSED, b.state());
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    void manyThreadsEveryCallEitherRunsOrIsRejected() throws Exception {
        CircuitBreaker b = breaker(5, 1_000_000, 1);
        int threads = 16, perThread = 500;
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            java.util.List<Future<?>> fs = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                fs.add(ex.submit(() -> {
                    await(go);
                    for (int i = 0; i < perThread; i++) {
                        try {
                            b.run(() -> { executed.incrementAndGet(); throw new IllegalStateException(); });
                        } catch (CircuitOpenException e) {
                            rejected.incrementAndGet();
                        } catch (IllegalStateException e) {
                            // counted failure
                        }
                    }
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(20, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
        assertEquals(threads * perThread, executed.get() + rejected.get());
        assertEquals(rejected.get(), b.rejectedCount());
        assertTrue(executed.get() >= 5, "at least the threshold must run");
        assertTrue(executed.get() <= 5 + threads, "only calls already in flight may exceed threshold, got " + executed.get());
        assertEquals(CircuitBreaker.State.OPEN, b.state());
    }

    @Test
    void exactlyOneProbeAmongManyConcurrentCallersAfterDeadline() throws Exception {
        CircuitBreaker b = breaker(1, 100, 1);
        fail(b);
        now.addAndGet(100);
        int threads = 12;
        AtomicInteger executed = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<Future<Boolean>> fs = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                fs.add(ex.submit(() -> {
                    await(go);
                    try {
                        b.call(() -> { executed.incrementAndGet(); await(hold); return 1; });
                        return true;
                    } catch (CircuitOpenException e) {
                        return false;
                    }
                }));
            }
            go.countDown();
            // wait until the rejected ones have finished; only the probe is left blocked
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (b.rejectedCount() < threads - 1 && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(threads - 1, b.rejectedCount());
            hold.countDown();
            int accepted = 0;
            for (Future<Boolean> f : fs) if (f.get(5, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertEquals(1, executed.get());
        } finally {
            hold.countDown();
            ex.shutdownNow();
        }
    }

    private static void await(CountDownLatch l) {
        try {
            if (!l.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
