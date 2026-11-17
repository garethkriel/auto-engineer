package batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MicroBatcherTest {

    /** Scheduler whose clock only moves when the test says so. */
    static final class ManualScheduler implements Scheduler {
        private record Task(long due, Runnable body, boolean[] cancelled) { }
        private final List<Task> tasks = new ArrayList<>();
        private long now;

        @Override
        public Runnable schedule(Runnable task, Duration delay) {
            boolean[] cancelled = new boolean[1];
            tasks.add(new Task(now + delay.toMillis(), task, cancelled));
            return () -> cancelled[0] = true;
        }

        /** Advances time and runs due, non-cancelled tasks in due order. */
        void advance(long millis) {
            now += millis;
            List<Task> due = new ArrayList<>();
            for (Task t : tasks) {
                if (t.due() <= now) {
                    due.add(t);
                }
            }
            tasks.removeAll(due);
            due.sort((a, b) -> Long.compare(a.due(), b.due()));
            for (Task t : due) {
                if (!t.cancelled()[0]) {
                    t.body().run();
                }
            }
        }

        long liveTasks() {
            return tasks.stream().filter(t -> !t.cancelled()[0]).count();
        }
    }

    private final ManualScheduler clock = new ManualScheduler();
    private final List<List<String>> batches = new ArrayList<>();
    private final Function<List<String>, List<String>> upper = in -> {
        batches.add(in);
        return in.stream().map(String::toUpperCase).toList();
    };

    private MicroBatcher<String, String> direct(int size) {
        return new MicroBatcher<>(upper, size, Duration.ofMillis(100), Runnable::run, clock);
    }

    @Test
    void dispatchesWhenBatchIsFullAndKeepsOrder() {
        var b = direct(3);
        var f1 = b.submit("a");
        var f2 = b.submit("b");
        assertFalse(f1.isDone());
        assertEquals(2, b.pendingCount());
        var f3 = b.submit("c");
        assertEquals(List.of(List.of("a", "b", "c")), batches);
        assertEquals("A", f1.join());
        assertEquals("B", f2.join());
        assertEquals("C", f3.join());
        assertEquals(0, b.pendingCount());
    }

    @Test
    void dispatchesAfterMaxWaitMeasuredFromFirstItem() {
        var b = direct(10);
        var f1 = b.submit("a");
        clock.advance(60);
        var f2 = b.submit("b");
        clock.advance(39);
        assertFalse(f1.isDone(), "99ms after first item: not due yet");
        clock.advance(1);
        assertEquals(List.of(List.of("a", "b")), batches);
        assertEquals("A", f1.join());
        assertEquals("B", f2.join());
    }

    @Test
    void sizeFlushCancelsTimerSoStaleTimerCannotFlushNextBatch() {
        var b = direct(2);
        b.submit("a");
        b.submit("b");                 // size flush, timer 1 cancelled
        assertEquals(0, clock.liveTasks());
        clock.advance(50);
        var f = b.submit("c");         // new batch, new timer due at 150
        clock.advance(50);             // original timer time (100) passes
        assertFalse(f.isDone(), "stale timer must not flush the new batch");
        clock.advance(50);
        assertEquals("C", f.join());
        assertEquals(List.of(List.of("a", "b"), List.of("c")), batches);
    }

    @Test
    void staleTimerThatWasNotCancelledIsIgnoredByGeneration() {
        // A scheduler that ignores cancellation, like a racing real timer.
        List<Runnable> fired = new ArrayList<>();
        Scheduler sloppy = (task, delay) -> {
            fired.add(task);
            return () -> { };
        };
        var b = new MicroBatcher<>(upper, 2, Duration.ofMillis(10), Runnable::run, sloppy);
        b.submit("a");
        b.submit("b");                       // size flush
        var f = b.submit("c");               // new batch
        fired.get(0).run();                  // stale timer of batch 1
        assertFalse(f.isDone());
        fired.get(1).run();                  // the real timer of batch 2
        assertEquals("C", f.join());
    }

    @Test
    void flushDispatchesNowAndIsNoOpWhenEmpty() {
        var b = direct(10);
        b.flush();
        assertTrue(batches.isEmpty());
        var f = b.submit("x");
        b.flush();
        assertEquals("X", f.join());
        assertEquals(0, clock.liveTasks());
    }

    @Test
    void closeFlushesPendingRejectsNewAndIsIdempotent() {
        var b = direct(10);
        var f = b.submit("x");
        b.close();
        b.close();
        assertEquals("X", f.join());
        assertThrows(IllegalStateException.class, () -> b.submit("y"));
    }

    @Test
    void bulkExceptionFailsEveryFutureOfThatBatchOnly() {
        RuntimeException boom = new RuntimeException("boom");
        var b = new MicroBatcher<String, String>(in -> {
            if (in.contains("bad")) {
                throw boom;
            }
            return in;
        }, 2, Duration.ofMillis(100), Runnable::run, clock);
        var f1 = b.submit("ok");
        var f2 = b.submit("bad");
        var f3 = b.submit("fine");
        var f4 = b.submit("fine2");
        CompletionException e = assertThrows(CompletionException.class, f1::join);
        assertSame(boom, e.getCause());
        assertTrue(f2.isCompletedExceptionally());
        assertEquals("fine", f3.join());
        assertEquals("fine2", f4.join());
    }

    @Test
    void wrongResultSizeAndNullResultFailTheBatch() {
        var small = new MicroBatcher<String, String>(in -> List.of("only"), 2, Duration.ofMillis(5), Runnable::run, clock);
        small.submit("a");
        var f = small.submit("b");
        var ex = assertThrows(CompletionException.class, f::join);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("1 results for 2 items"));

        var nul = new MicroBatcher<String, String>(in -> null, 1, Duration.ofMillis(5), Runnable::run, clock);
        var g = nul.submit("a");
        assertTrue(assertThrows(CompletionException.class, g::join).getCause().getMessage().contains("null"));
    }

    @Test
    void nullResultElementIsAllowed() {
        var b = new MicroBatcher<String, String>(in -> Collections.singletonList(null), 1, Duration.ofMillis(5), Runnable::run, clock);
        assertEquals(null, b.submit("a").join());
    }

    @Test
    void rejectingWorkerFailsBatchFutures() {
        var b = new MicroBatcher<String, String>(upper, 2, Duration.ofMillis(5),
                r -> { throw new RejectedExecutionException("full"); }, clock);
        b.submit("a");
        var f = b.submit("b");
        assertTrue(f.isCompletedExceptionally());
        var ex = assertThrows(CompletionException.class, f::join);
        assertInstanceOf(RejectedExecutionException.class, ex.getCause());
        assertEquals(0, b.pendingCount());
    }

    @Test
    void cancellingOneFutureDoesNotAffectBatchMates() {
        var b = direct(3);
        var f1 = b.submit("a");
        var f2 = b.submit("b");
        f1.cancel(true);
        var f3 = b.submit("c");
        assertEquals(List.of("a", "b", "c"), batches.get(0), "cancelled item still travels with its batch");
        assertTrue(f1.isCancelled());
        assertEquals("B", f2.join());
        assertEquals("C", f3.join());
    }

    @Test
    void bulkRunsOnWorkerNotOnSubmitterAndOutsideTheLock() {
        Deque<Runnable> queue = new ArrayDeque<>();
        var b = new MicroBatcher<String, String>(in -> {
            // re-entering the batcher from the bulk function must not deadlock
            return in;
        }, 2, Duration.ofMillis(100), queue::add, clock);
        var f1 = b.submit("a");
        var f2 = b.submit("b");
        assertEquals(1, queue.size());
        assertFalse(f1.isDone(), "nothing ran until the worker runs it");
        queue.poll().run();
        assertEquals("a", f1.join());
        assertEquals("b", f2.join());
    }

    @Test
    void bulkMayResubmitToTheSameBatcher() {
        MicroBatcher<String, String>[] self = newHolder();
        self[0] = new MicroBatcher<>(in -> {
            if (in.contains("first")) {
                self[0].submit("again"); // would deadlock if the lock were held
            }
            return in;
        }, 1, Duration.ofMillis(5), Runnable::run, clock);
        assertEquals("first", self[0].submit("first").join());
    }

    @SuppressWarnings("unchecked")
    private static MicroBatcher<String, String>[] newHolder() {
        return new MicroBatcher[1];
    }

    @Test
    void batchHandedToBulkIsImmutableSnapshot() {
        var b = new MicroBatcher<String, String>(in -> {
            assertThrows(UnsupportedOperationException.class, () -> in.add("x"));
            return in;
        }, 1, Duration.ofMillis(5), Runnable::run, clock);
        assertEquals("a", b.submit("a").join());
    }

    @Test
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new MicroBatcher<String, String>(upper, 0, Duration.ofMillis(1), Runnable::run, clock));
        assertThrows(IllegalArgumentException.class, () -> new MicroBatcher<String, String>(upper, 1, Duration.ZERO, Runnable::run, clock));
        assertThrows(IllegalArgumentException.class, () -> new MicroBatcher<String, String>(upper, 1, Duration.ofMillis(-1), Runnable::run, clock));
        assertThrows(NullPointerException.class, () -> new MicroBatcher<String, String>(null, 1, Duration.ofMillis(1), Runnable::run, clock));
        assertThrows(NullPointerException.class, () -> direct(2).submit(null));
    }

    @Test
    void realThreadsEveryItemGetsItsOwnResultAndBatchesAreBounded() throws Exception {
        int threads = 16;
        int perThread = 200;
        int maxSize = 7;
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        ExecutorService producers = Executors.newFixedThreadPool(threads);
        List<Integer> sizes = Collections.synchronizedList(new ArrayList<>());
        try {
            var b = new MicroBatcher<Integer, Integer>(in -> {
                sizes.add(in.size());
                return in.stream().map(i -> i * 2).toList();
            }, maxSize, Duration.ofMillis(5), workers, Scheduler.of(timer));
            List<CompletableFuture<Void>> checks = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<Void>> producerDone = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                producerDone.add(CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < perThread; i++) {
                        int v = base + i;
                        checks.add(b.submit(v).thenAccept(r -> assertEquals(v * 2, r)));
                    }
                }, producers));
            }
            for (var p : producerDone) {
                p.get(10, TimeUnit.SECONDS);
            }
            for (var c : new ArrayList<>(checks)) {
                c.get(10, TimeUnit.SECONDS);
            }
            b.close();
            assertEquals(threads * perThread, checks.size());
            assertEquals(threads * perThread, sizes.stream().mapToInt(Integer::intValue).sum());
            assertTrue(sizes.stream().allMatch(s -> s >= 1 && s <= maxSize), "sizes " + new HashSet<>(sizes));
        } finally {
            producers.shutdownNow();
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    @Test
    void realSchedulerFlushesLoneItemAfterMaxWait() throws Exception {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try {
            var b = new MicroBatcher<String, String>(upper, 100, Duration.ofMillis(20), Runnable::run, Scheduler.of(timer));
            assertEquals("Z", b.submit("z").get(5, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
            throw e;
        } finally {
            timer.shutdownNow();
        }
    }
}
