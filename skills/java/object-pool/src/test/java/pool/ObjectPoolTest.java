package pool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ObjectPoolTest {
    /** Distinct identity-compared token. */
    private static final class Res {
        final int id;
        Res(int id) { this.id = id; }
    }

    private final AtomicInteger made = new AtomicInteger();
    private final List<Res> destroyed = Collections.synchronizedList(new ArrayList<>());

    private ObjectPool<Res> pool(int max) {
        return new ObjectPool<>(max, () -> new Res(made.incrementAndGet()), r -> true, destroyed::add);
    }

    @Test
    void createsLazilyAndReusesMostRecentlyReleased() throws Exception {
        try (ObjectPool<Res> p = pool(3)) {
            assertEquals(0, made.get());
            Res a = p.acquire(0);
            Res b = p.acquire(0);
            assertEquals(2, p.borrowedCount());
            p.release(a);
            p.release(b);
            assertEquals(2, p.idleCount());
            assertSame(b, p.acquire(0));
            assertSame(a, p.acquire(0));
            assertEquals(2, made.get());
            assertEquals(2, p.size());
        }
    }

    @Test
    void zeroTimeoutFailsFastWhenExhausted() throws Exception {
        try (ObjectPool<Res> p = pool(1)) {
            p.acquire(0);
            assertThrows(TimeoutException.class, () -> p.acquire(0));
        }
    }

    @Test
    void timeoutWaitsAtLeastRoughlyTheRequestedTime() throws Exception {
        try (ObjectPool<Res> p = pool(1)) {
            p.acquire(0);
            long t0 = System.nanoTime();
            assertThrows(TimeoutException.class, () -> p.acquire(80));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) >= 70);
        }
    }

    @Test
    void releaseWakesBlockedAcquirer() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (ObjectPool<Res> p = pool(1)) {
            Res a = p.acquire(0);
            Future<Res> f = ex.submit(() -> p.acquire(5000));
            Thread.sleep(100);
            assertFalse(f.isDone());
            p.release(a);
            assertSame(a, f.get(2, TimeUnit.SECONDS));
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void invalidFreeIdleObjectsAreDestroyedAndReplaced() throws Exception {
        ObjectPool<Res> p = new ObjectPool<>(2, () -> new Res(made.incrementAndGet()),
                r -> r.id != 1, destroyed::add);
        Res first = p.acquire(0);
        assertEquals(1, first.id);
        p.release(first);
        Res next = p.acquire(0);
        assertEquals(2, next.id);
        assertEquals(List.of(first), destroyed);
        assertEquals(1, p.size());
        assertEquals(1, p.borrowedCount());
    }

    @Test
    void throwingValidatorCountsAsInvalid() throws Exception {
        ObjectPool<Res> p = new ObjectPool<>(1, () -> new Res(made.incrementAndGet()),
                r -> { throw new IllegalStateException("boom"); }, destroyed::add);
        Res a = p.acquire(0);
        p.release(a);
        Res b = p.acquire(0);
        assertNotSame(a, b);
        assertEquals(1, destroyed.size());
    }

    @Test
    void factoryFailureFreesTheSlot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ObjectPool<Res> p = new ObjectPool<>(1, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new UnsupportedOperationException("no");
            }
            return new Res(calls.get());
        }, r -> true, r -> { });
        assertThrows(UnsupportedOperationException.class, () -> p.acquire(0));
        assertEquals(0, p.size());
        assertNotNull(p.acquire(0));
    }

    @Test
    void nullFromFactoryIsIllegalStateAndFreesSlot() throws Exception {
        ObjectPool<Res> p = new ObjectPool<>(1, () -> null);
        assertThrows(IllegalStateException.class, () -> p.acquire(0));
        assertEquals(0, p.size());
    }

    @Test
    void invalidateDestroysAndFreesSlot() throws Exception {
        try (ObjectPool<Res> p = pool(1)) {
            Res a = p.acquire(0);
            p.invalidate(a);
            assertEquals(List.of(a), destroyed);
            assertEquals(0, p.size());
            assertNotSame(a, p.acquire(0));
            assertThrows(IllegalArgumentException.class, () -> p.invalidate(a));
        }
    }

    @Test
    void doubleOrForeignReleaseRejectedAndHarmless() throws Exception {
        try (ObjectPool<Res> p = pool(2)) {
            Res a = p.acquire(0);
            p.release(a);
            assertThrows(IllegalArgumentException.class, () -> p.release(a));
            assertThrows(IllegalArgumentException.class, () -> p.release(new Res(99)));
            assertEquals(1, p.idleCount());
            assertEquals(0, p.borrowedCount());
        }
    }

    @Test
    void closeDestroysIdleRejectsAcquireAndIsIdempotent() throws Exception {
        ObjectPool<Res> p = pool(3);
        Res a = p.acquire(0);
        Res b = p.acquire(0);
        p.release(a);
        p.close();
        p.close();
        assertEquals(List.of(a), destroyed);
        assertThrows(IllegalStateException.class, () -> p.acquire(0));
        p.release(b);
        assertEquals(List.of(a, b), destroyed);
        assertEquals(0, p.size());
    }

    @Test
    void closeWakesWaiters() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        ObjectPool<Res> p = pool(1);
        p.acquire(0);
        Future<Res> f = ex.submit(() -> p.acquire(10_000));
        Thread.sleep(100);
        p.close();
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        ex.shutdownNow();
    }

    @Test
    void throwingDestroyerDoesNotCorruptAccounting() throws Exception {
        ObjectPool<Res> p = new ObjectPool<>(1, () -> new Res(made.incrementAndGet()), r -> true,
                r -> { throw new RuntimeException("cannot destroy"); });
        Res a = p.acquire(0);
        p.invalidate(a);
        assertEquals(0, p.size());
        assertNotNull(p.acquire(0));
    }

    @Test
    void interruptWhileWaitingPropagates() throws Exception {
        ObjectPool<Res> p = pool(1);
        p.acquire(0);
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> p.acquire(1000));
        assertFalse(Thread.currentThread().isInterrupted(), "interrupt flag is cleared by the exception");
        assertEquals(1, p.size());
    }

    @Test
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectPool<>(0, Object::new));
        assertThrows(NullPointerException.class, () -> new ObjectPool<Object>(1, null));
        assertThrows(NullPointerException.class, () -> new ObjectPool<>(1, Object::new, null, o -> { }));
        assertThrows(NullPointerException.class, () -> new ObjectPool<>(1, Object::new, o -> true, null));
        ObjectPool<Object> p = new ObjectPool<>(1, Object::new);
        assertThrows(IllegalArgumentException.class, () -> p.acquire(-1));
    }

    @Test
    void stressNeverExceedsMaxAndNeverSharesAnObject() throws Exception {
        final int max = 4;
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Set<Res> held = ConcurrentHashMap.newKeySet();
        ObjectPool<Res> p = pool(max);
        ExecutorService ex = Executors.newFixedThreadPool(12);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 12; t++) {
            fs.add(ex.submit(() -> {
                for (int i = 0; i < 300; i++) {
                    Res r = p.acquire(10_000);
                    assertTrue(held.add(r), "object handed to two borrowers");
                    peak.accumulateAndGet(inUse.incrementAndGet(), Math::max);
                    Thread.yield();
                    inUse.decrementAndGet();
                    held.remove(r);
                    if (i % 17 == 0) {
                        p.invalidate(r);
                    } else {
                        p.release(r);
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get(30, TimeUnit.SECONDS);
        }
        ex.shutdown();
        assertTrue(peak.get() <= max, "peak " + peak.get());
        assertEquals(0, p.borrowedCount());
        assertEquals(p.idleCount(), p.size());
        assertTrue(p.size() <= max);
        p.close();
        assertEquals(made.get(), destroyed.size(), "every created object destroyed exactly once");
        assertEquals(made.get(), new java.util.HashSet<>(destroyed).size());
    }
}
