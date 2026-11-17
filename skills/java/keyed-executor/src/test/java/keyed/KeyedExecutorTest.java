package keyed;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeyedExecutorTest {
    private ExecutorService pool;
    private KeyedExecutor keyed;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4);
        keyed = new KeyedExecutor(pool);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void sameKeyRunsInSubmissionOrderAndNeverOverlaps() throws Exception {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        List<CompletableFuture<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int n = i;
            fs.add(keyed.submit("a", () -> {
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                order.add(n);
                running.decrementAndGet();
                return n;
            }));
        }
        for (int i = 0; i < 50; i++) {
            assertEquals(i, fs.get(i).get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, maxRunning.get());
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 50; i++) expected.add(i);
        assertEquals(expected, order);
    }

    @Test
    void differentKeysRunInParallel() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        CompletableFuture<Integer> a = keyed.submit("a", () -> barrier.await(5, TimeUnit.SECONDS));
        CompletableFuture<Integer> b = keyed.submit("b", () -> barrier.await(5, TimeUnit.SECONDS));
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
        assertTrue(a.isDone() && !a.isCompletedExceptionally());
        assertTrue(b.isDone() && !b.isCompletedExceptionally());
    }

    @Test
    void failureDoesNotStopLaterTasksOfTheKey() throws Exception {
        CompletableFuture<String> bad = keyed.submit("k", () -> {
            throw new IllegalStateException("boom");
        });
        CompletableFuture<String> good = keyed.submit("k", () -> "ok");
        ExecutionException e = assertThrows(ExecutionException.class, () -> bad.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals("boom", e.getCause().getMessage());
        assertEquals("ok", good.get(5, TimeUnit.SECONDS));
    }

    @Test
    void keyIsRemovedWhenQueueDrains() throws Exception {
        for (int i = 0; i < 20; i++) keyed.submit("k" + i, () -> 1);
        assertTrue(keyed.awaitIdle(Duration.ofSeconds(5)));
        assertEquals(0, keyed.activeKeys());
    }

    @Test
    void activeKeysCountsDistinctBusyKeys() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        for (String k : List.of("a", "b")) {
            keyed.submit(k, () -> {
                started.countDown();
                return release.await(5, TimeUnit.SECONDS);
            });
        }
        keyed.submit("a", () -> 2); // queued behind a's running task
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(2, keyed.activeKeys());
        assertFalse(keyed.awaitIdle(Duration.ofMillis(50)));
        release.countDown();
        assertTrue(keyed.awaitIdle(Duration.ofSeconds(5)));
        assertEquals(0, keyed.activeKeys());
    }

    @Test
    void cancelledBeforeItsTurnIsSkipped() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        CompletableFuture<Boolean> first = keyed.submit("k", () -> release.await(5, TimeUnit.SECONDS));
        CompletableFuture<Integer> second = keyed.submit("k", ran::incrementAndGet);
        CompletableFuture<Integer> third = keyed.submit("k", () -> 100 + ran.incrementAndGet());
        assertTrue(second.cancel(false));
        release.countDown();
        assertTrue(first.get(5, TimeUnit.SECONDS));
        assertEquals(101, third.get(5, TimeUnit.SECONDS));
        assertEquals(1, ran.get());
        assertTrue(second.isCancelled());
    }

    @Test
    void shutdownRejectsNewButFinishesAccepted() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> f = keyed.submit("k", () -> release.await(5, TimeUnit.SECONDS));
        CompletableFuture<String> g = keyed.submit("k", () -> "after");
        keyed.shutdown();
        assertThrows(RejectedExecutionException.class, () -> keyed.submit("other", () -> 1));
        assertThrows(RejectedExecutionException.class, () -> keyed.submit("k", () -> 1));
        release.countDown();
        assertTrue(f.get(5, TimeUnit.SECONDS));
        assertEquals("after", g.get(5, TimeUnit.SECONDS));
        assertTrue(keyed.awaitIdle(Duration.ofSeconds(5)));
    }

    @Test
    void delegateRejectionRollsBackAndLeavesNothingQueued() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Executor flaky = r -> {
            if (calls.getAndIncrement() == 0) throw new RejectedExecutionException("full");
            pool.execute(r);
        };
        KeyedExecutor k = new KeyedExecutor(flaky);
        assertThrows(RejectedExecutionException.class, () -> k.submit("x", () -> 1));
        assertEquals(0, k.activeKeys());
        assertEquals(7, k.submit("x", () -> 7).get(5, TimeUnit.SECONDS));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new KeyedExecutor(null));
        assertThrows(NullPointerException.class, () -> keyed.submit(null, () -> 1));
        assertThrows(NullPointerException.class, () -> keyed.submit("k", null));
        assertThrows(NullPointerException.class, () -> keyed.awaitIdle(null));
    }

    @Test
    void hotKeyDoesNotStarveOtherKeysOnSingleThread() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            KeyedExecutor k = new KeyedExecutor(single);
            CountDownLatch gate = new CountDownLatch(1);
            single.execute(() -> {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 5; i++) {
                int n = i;
                k.submit("hot", () -> log.add("hot" + n));
            }
            CompletableFuture<Boolean> cold = k.submit("cold", () -> log.add("cold"));
            gate.countDown(); // everything is queued while the only worker is blocked
            cold.get(5, TimeUnit.SECONDS);
            assertTrue(k.awaitIdle(Duration.ofSeconds(5)));
            // One worker turn per task: hot0, then cold (queued behind hot's first turn), then the rest.
            assertEquals(List.of("hot0", "cold", "hot1", "hot2", "hot3", "hot4"), log);
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void stressManyKeysKeepPerKeyOrderWithPlainCounters() throws Exception {
        int keys = 8, perKey = 200;
        int[] counters = new int[keys]; // deliberately not atomic: safe only if serial per key
        List<List<Integer>> seen = new ArrayList<>();
        for (int i = 0; i < keys; i++) seen.add(new ArrayList<>());
        List<CompletableFuture<Integer>> all = new ArrayList<>();
        for (int j = 0; j < perKey; j++) {
            for (int i = 0; i < keys; i++) {
                int key = i, seq = j;
                all.add(keyed.submit(key, () -> {
                    counters[key]++;
                    seen.get(key).add(seq);
                    return counters[key];
                }));
            }
        }
        CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        assertTrue(keyed.awaitIdle(Duration.ofSeconds(5)));
        for (int i = 0; i < keys; i++) {
            assertEquals(perKey, counters[i]);
            for (int j = 0; j < perKey; j++) assertEquals(j, seen.get(i).get(j));
        }
        assertEquals(0, keyed.activeKeys());
    }
}
