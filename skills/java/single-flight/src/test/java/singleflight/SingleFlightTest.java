package singleflight;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SingleFlightTest {
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final SingleFlight<String, Integer> sf = new SingleFlight<>(pool);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void concurrentCallsForSameKeyShareOneExecution() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            runs.incrementAndGet();
            release.await();
            return 42;
        };
        List<CompletableFuture<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fs.add(sf.run("k", task));
        }
        assertEquals(1, sf.inFlight());
        release.countDown();
        for (CompletableFuture<Integer> f : fs) {
            assertEquals(42, f.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, runs.get());
    }

    @Test
    void keyIsForgottenAfterCompletion() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        assertEquals(1, sf.run("k", runs::incrementAndGet).get(5, TimeUnit.SECONDS));
        awaitIdle();
        assertEquals(2, sf.run("k", runs::incrementAndGet).get(5, TimeUnit.SECONDS));
        assertEquals(2, runs.get());
    }

    @Test
    void differentKeysRunIndependently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        Callable<Integer> a = () -> {
            bothStarted.countDown();
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
            return 1;
        };
        Callable<Integer> b = () -> {
            bothStarted.countDown();
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
            return 2;
        };
        CompletableFuture<Integer> fa = sf.run("a", a);
        CompletableFuture<Integer> fb = sf.run("b", b);
        assertEquals(1, fa.get(5, TimeUnit.SECONDS));
        assertEquals(2, fb.get(5, TimeUnit.SECONDS));
    }

    @Test
    void failureReachesEveryCallerAndDoesNotPoisonKey() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        IllegalStateException boom = new IllegalStateException("boom");
        Callable<Integer> bad = () -> {
            release.await();
            throw boom;
        };
        CompletableFuture<Integer> f1 = sf.run("k", bad);
        CompletableFuture<Integer> f2 = sf.run("k", bad);
        release.countDown();
        for (CompletableFuture<Integer> f : List.of(f1, f2)) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
            assertSame(boom, e.getCause());
        }
        awaitIdle();
        assertEquals(7, sf.run("k", () -> 7).get(5, TimeUnit.SECONDS));
    }

    @Test
    void checkedExceptionIsTheCause() {
        CompletableFuture<Integer> f = sf.run("k", () -> {
            throw new java.io.IOException("io");
        });
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertInstanceOf(java.io.IOException.class, e.getCause());
    }

    @Test
    void cancellingOneCallerDoesNotAffectOthers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            release.await();
            return 5;
        };
        CompletableFuture<Integer> f1 = sf.run("k", task);
        CompletableFuture<Integer> f2 = sf.run("k", task);
        assertTrue(f1.cancel(true));
        release.countDown();
        assertEquals(5, f2.get(5, TimeUnit.SECONDS));
        assertTrue(f1.isCancelled());
        // key was released despite the cancelled caller
        awaitIdle();
        assertEquals(0, sf.inFlight());
    }

    @Test
    void rejectedExecutionFailsFutureAndLeavesNoKeyInFlight() {
        Executor rejecting = r -> {
            throw new RejectedExecutionException("full");
        };
        SingleFlight<String, Integer> s = new SingleFlight<>(rejecting);
        CompletableFuture<Integer> f = s.run("k", () -> 1);
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(1, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, e.getCause());
        assertEquals(0, s.inFlight());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> sf.run(null, () -> 1));
        assertThrows(NullPointerException.class, () -> sf.run("k", null));
        assertThrows(NullPointerException.class, () -> new SingleFlight<String, Integer>(null));
        assertEquals(0, sf.inFlight());
    }

    @Test
    void nullResultIsAllowed() throws Exception {
        assertNull(sf.run("k", () -> null).get(5, TimeUnit.SECONDS));
    }

    @Test
    void continuationCallingRunAgainStartsFreshExecution() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CompletableFuture<Integer> second = new CompletableFuture<>();
        sf.run("k", runs::incrementAndGet)
                .thenAccept(v -> sf.run("k", runs::incrementAndGet).whenComplete((x, t) -> {
                    if (t != null) second.completeExceptionally(t);
                    else second.complete(x);
                }));
        assertEquals(2, second.get(5, TimeUnit.SECONDS));
        assertEquals(2, runs.get());
    }

    @Test
    void inFlightCountsKeysNotCallers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            release.await();
            return 1;
        };
        sf.run("a", task);
        sf.run("a", task);
        sf.run("b", task);
        assertEquals(2, sf.inFlight());
        release.countDown();
        awaitIdle();
        assertEquals(0, sf.inFlight());
    }

    @Test
    void stressManyThreadsManyKeysEveryCallerGetsItsKeysValue() throws Exception {
        int keys = 5;
        int callers = 64;
        AtomicInteger runs = new AtomicInteger();
        ExecutorService callersPool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            CyclicBarrier barrier = new CyclicBarrier(callers);
            for (int c = 0; c < callers; c++) {
                final int key = c % keys;
                results.add(callersPool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    int v = sf.run("k" + key, () -> {
                        runs.incrementAndGet();
                        Thread.sleep(20);
                        return key * 10;
                    }).get(10, TimeUnit.SECONDS);
                    return v == key * 10;
                }));
            }
            for (Future<Boolean> r : results) {
                assertTrue(r.get(20, TimeUnit.SECONDS));
            }
        } finally {
            callersPool.shutdownNow();
        }
        assertTrue(runs.get() >= keys, "at least one run per key");
        assertTrue(runs.get() < callers, "some calls must have been coalesced");
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sf.inFlight() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(0, sf.inFlight());
    }
}
