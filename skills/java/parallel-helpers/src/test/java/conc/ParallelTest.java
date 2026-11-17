package conc;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParallelTest {
    private ExecutorService pool;
    private ScheduledThreadPoolExecutor sched;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(8);
        sched = new ScheduledThreadPoolExecutor(1);
        sched.setRemoveOnCancelPolicy(true);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        sched.shutdownNow();
    }

    // ---- mapOrdered ----

    @Test
    void keepsInputOrderEvenWhenLaterItemsFinishFirst() {
        List<Integer> in = List.of(0, 1, 2, 3, 4, 5);
        List<Integer> out = Parallel.mapOrdered(pool, in, i -> {
            sleep((6 - i) * 15L);
            return i * 10;
        }, 6);
        assertEquals(List.of(0, 10, 20, 30, 40, 50), out);
    }

    @Test
    void emptyInputGivesEmptyList() {
        assertEquals(List.of(), Parallel.mapOrdered(pool, List.<Integer>of(), i -> i, 3));
    }

    @Test
    void reachesButNeverExceedsTheLimit() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(3); // proves 3 really run together
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            in.add(i);
        }
        List<Integer> out = Parallel.mapOrdered(pool, in, i -> {
            int now = running.incrementAndGet();
            max.accumulateAndGet(now, Math::max);
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            running.decrementAndGet();
            return i;
        }, 3);
        assertEquals(in, out);
        assertEquals(3, max.get());
    }

    @Test
    void limitOneRunsSequentiallyAndStopsStartingAfterFailure() {
        AtomicInteger started = new AtomicInteger();
        ParallelException e = assertThrows(ParallelException.class, () ->
                Parallel.mapOrdered(pool, List.of(0, 1, 2, 3, 4, 5, 6, 7), i -> {
                    started.incrementAndGet();
                    if (i == 2) {
                        throw new IllegalStateException("boom " + i);
                    }
                    return i;
                }, 1));
        assertEquals(3, started.get());
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals("boom 2", e.getCause().getMessage());
    }

    @Test
    void failsFastWithoutWaitingForSlowRunningItem() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            ParallelException e = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThrows(ParallelException.class, () ->
                            Parallel.mapOrdered(pool, List.of(0, 1), i -> {
                                if (i == 0) {
                                    awaitQuietly(release);
                                    return 0;
                                }
                                throw new UnsupportedOperationException("fast failure");
                            }, 2)));
            assertInstanceOf(UnsupportedOperationException.class, e.getCause());
        } finally {
            release.countDown();
        }
    }

    @Test
    void firstFailureInTimeWins() {
        CountDownLatch firstFailed = new CountDownLatch(1);
        ParallelException e = assertThrows(ParallelException.class, () ->
                Parallel.mapOrdered(pool, List.of(0, 1), i -> {
                    if (i == 1) {
                        firstFailed.countDown();
                        throw new IllegalArgumentException("first");
                    }
                    awaitQuietly(firstFailed);
                    sleep(200); // let the first failure be recorded before this one is thrown
                    throw new IllegalStateException("second");
                }, 2));
        assertEquals("first", e.getCause().getMessage());
    }

    @Test
    void interruptedCallerRestoresFlagAndThrows() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger flagSeen = new AtomicInteger(-1);
        CompletableFuture<Throwable> caught = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                Parallel.mapOrdered(pool, List.of(1), i -> {
                    awaitQuietly(release);
                    return i;
                }, 1);
                caught.complete(null);
            } catch (Throwable x) {
                flagSeen.set(Thread.currentThread().isInterrupted() ? 1 : 0);
                caught.complete(x);
            }
        });
        t.start();
        sleep(100);
        t.interrupt();
        Throwable x = caught.get(5, TimeUnit.SECONDS);
        release.countDown();
        assertInstanceOf(ParallelException.class, x);
        assertInstanceOf(InterruptedException.class, x.getCause());
        assertEquals(1, flagSeen.get());
    }

    @Test
    void rejectedExecutionPropagatesWithoutHanging() {
        pool.shutdown();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThrows(RejectedExecutionException.class,
                        () -> Parallel.mapOrdered(pool, List.of(1, 2, 3), i -> i, 1)));
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> Parallel.mapOrdered(pool, List.of(1), i -> i, 0));
        assertThrows(NullPointerException.class, () -> Parallel.mapOrdered(pool, null, i -> i, 1));
        assertThrows(NullPointerException.class, () -> Parallel.mapOrdered(null, List.of(1), i -> i, 1));
        assertThrows(NullPointerException.class, () -> Parallel.<Integer, Integer>mapOrdered(pool, List.of(1), null, 1));
    }

    // ---- firstSuccess ----

    @Test
    void firstSuccessSkipsFailuresAndReturnsAWinner() throws Exception {
        List<Supplier<String>> tasks = List.of(
                () -> { throw new IllegalStateException("a"); },
                () -> "ok",
                () -> { throw new IllegalStateException("c"); });
        assertEquals("ok", Parallel.firstSuccess(pool, tasks).get(5, TimeUnit.SECONDS));
    }

    @Test
    void firstSuccessPrefersFastOverSlow() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try {
            List<Supplier<String>> tasks = List.of(
                    () -> { awaitQuietly(release); return "slow"; },
                    () -> "fast");
            assertEquals("fast", Parallel.firstSuccess(pool, tasks).get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void firstSuccessReportsEveryCauseWhenAllFail() {
        List<Supplier<String>> tasks = List.of(
                () -> { throw new IllegalStateException("a"); },
                () -> { throw new IllegalArgumentException("b"); },
                () -> { throw new UnsupportedOperationException("c"); });
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> Parallel.firstSuccess(pool, tasks).get(5, TimeUnit.SECONDS));
        AllFailedException all = assertInstanceOf(AllFailedException.class, e.getCause());
        assertEquals(3, all.causes().size());
        assertEquals(List.of("a", "b", "c"),
                all.causes().stream().map(Throwable::getMessage).sorted().toList());
    }

    @Test
    void firstSuccessSkipsUnstartedSuppliersOnceWon() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger ran = new AtomicInteger();
            List<Supplier<Integer>> tasks = List.of(
                    () -> { ran.incrementAndGet(); return 1; },
                    () -> { ran.incrementAndGet(); return 2; },
                    () -> { ran.incrementAndGet(); return 3; });
            assertEquals(1, Parallel.firstSuccess(single, tasks).get(5, TimeUnit.SECONDS));
            single.shutdown();
            assertTrue(single.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, ran.get());
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void firstSuccessRejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> Parallel.firstSuccess(pool, List.<Supplier<Integer>>of()));
    }

    @Test
    void firstSuccessCountsRejectedSubmissionsAsFailures() {
        pool.shutdown();
        ExecutionException e = assertThrows(ExecutionException.class, () ->
                Parallel.firstSuccess(pool, List.<Supplier<Integer>>of(() -> 1, () -> 2)).get(5, TimeUnit.SECONDS));
        AllFailedException all = assertInstanceOf(AllFailedException.class, e.getCause());
        assertEquals(2, all.causes().size());
    }

    // ---- withTimeout ----

    @Test
    void timeoutFiresWhenSourceNeverCompletes() {
        CompletableFuture<String> never = new CompletableFuture<>();
        ExecutionException e = assertThrows(ExecutionException.class, () ->
                Parallel.withTimeout(never, Duration.ofMillis(50), sched).get(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, e.getCause());
        assertFalse(never.isDone(), "source is left alone");
    }

    @Test
    void sourceResultWinsAndTimerIsCancelled() throws Exception {
        CompletableFuture<String> src = new CompletableFuture<>();
        CompletableFuture<String> out = Parallel.withTimeout(src, Duration.ofSeconds(30), sched);
        assertEquals(1, sched.getQueue().size());
        src.complete("done");
        assertEquals("done", out.get(5, TimeUnit.SECONDS));
        assertEquals(0, sched.getQueue().size());
    }

    @Test
    void sourceFailurePropagates() {
        CompletableFuture<String> src = CompletableFuture.failedFuture(new IllegalStateException("bad"));
        ExecutionException e = assertThrows(ExecutionException.class, () ->
                Parallel.withTimeout(src, Duration.ofSeconds(30), sched).get(5, TimeUnit.SECONDS));
        assertEquals("bad", e.getCause().getMessage());
    }

    @Test
    void negativeTimeoutRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                Parallel.withTimeout(new CompletableFuture<String>(), Duration.ofMillis(-1), sched));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
