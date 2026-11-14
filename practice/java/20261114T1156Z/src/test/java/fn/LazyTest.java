package fn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LazyTest {
    @Test
    void doesNotComputeUntilGet() {
        AtomicInteger calls = new AtomicInteger();
        Lazy<Integer> lazy = Lazy.of(calls::incrementAndGet);
        assertEquals(0, calls.get());
        assertFalse(lazy.isEvaluated());
        assertEquals(1, lazy.get());
        assertTrue(lazy.isEvaluated());
    }

    @Test
    void computesOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        Lazy<Integer> lazy = Lazy.of(calls::incrementAndGet);
        lazy.get();
        lazy.get();
        assertEquals(1, lazy.get());
        assertEquals(1, calls.get());
    }

    @Test
    void nullResultIsMemoised() {
        AtomicInteger calls = new AtomicInteger();
        Lazy<String> lazy = Lazy.of(() -> { calls.incrementAndGet(); return null; });
        assertNull(lazy.get());
        assertNull(lazy.get());
        assertEquals(1, calls.get());
        assertTrue(lazy.isEvaluated());
    }

    @Test
    void failureIsNotCachedAndRetried() {
        AtomicInteger calls = new AtomicInteger();
        Lazy<Integer> lazy = Lazy.of(() -> {
            if (calls.incrementAndGet() < 3) throw new IllegalStateException("boom" + calls.get());
            return 42;
        });
        assertThrows(IllegalStateException.class, lazy::get);
        assertFalse(lazy.isEvaluated());
        assertThrows(IllegalStateException.class, lazy::get);
        assertEquals(42, lazy.get());
        assertEquals(3, calls.get());
    }

    @Test
    void mapIsLazyAndMemoised() {
        List<String> trace = new ArrayList<>();
        Lazy<Integer> base = Lazy.of(() -> { trace.add("base"); return 5; });
        Lazy<String> mapped = base.map(i -> { trace.add("map"); return "v" + i; });
        assertEquals(List.of(), trace);
        assertEquals("v5", mapped.get());
        assertEquals("v5", mapped.get());
        assertEquals(List.of("base", "map"), trace);
        assertTrue(base.isEvaluated());
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> Lazy.of(null));
        assertThrows(NullPointerException.class, () -> Lazy.of(() -> 1).map(null));
    }

    @Test
    void concurrentGetComputesOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Lazy<Integer> lazy = Lazy.of(() -> {
            calls.incrementAndGet();
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return 7;
        });
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> { start.await(); return lazy.get(); }));
            }
            start.countDown();
            for (Future<Integer> f : fs) assertEquals(7, f.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, calls.get());
    }
}
