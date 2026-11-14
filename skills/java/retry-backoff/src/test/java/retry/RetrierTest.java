package retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RetrierTest {
    private final List<Duration> sleeps = new ArrayList<>();
    private final Retrier retrier = new Retrier(
            new RetryPolicy(4, Duration.ofMillis(100), 2, Duration.ofMillis(300)), sleeps::add);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private static boolean isIo(Exception e) {
        return e instanceof IOException;
    }

    @Test
    void succeedsFirstTimeWithoutSleeping() throws Exception {
        assertEquals("ok", retrier.call(() -> "ok", RetrierTest::isIo));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void retriesUntilSuccessWithBackoffDelays() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = retrier.call(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IOException("flaky");
            }
            return "done";
        }, RetrierTest::isIo);
        assertEquals("done", result);
        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), sleeps);
    }

    @Test
    void exhaustionReportsAttemptsDelayAndCause() {
        AtomicInteger calls = new AtomicInteger();
        IOException last = new IOException("always");
        RetryExhaustedException ex = assertThrows(RetryExhaustedException.class, () -> retrier.call(() -> {
            calls.incrementAndGet();
            throw last;
        }, RetrierTest::isIo));
        assertEquals(4, calls.get());
        assertEquals(4, ex.attempts());
        assertEquals(Duration.ofMillis(600), ex.totalDelay());
        assertSame(last, ex.getCause());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(300)), sleeps);
    }

    @Test
    void nonRetryableFailurePropagatesImmediately() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> retrier.call(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("bug");
        }, RetrierTest::isIo));
        assertEquals("bug", ex.getMessage());
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void singleAttemptPolicyNeverSleeps() {
        Retrier once = new Retrier(new RetryPolicy(1, Duration.ofSeconds(1), 2, Duration.ofSeconds(1)), sleeps::add);
        RetryExhaustedException ex = assertThrows(RetryExhaustedException.class,
                () -> once.call(() -> { throw new IOException("x"); }, RetrierTest::isIo));
        assertEquals(1, ex.attempts());
        assertEquals(Duration.ZERO, ex.totalDelay());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void interruptDuringSleepRestoresFlagAndThrows() {
        Retrier r = new Retrier(new RetryPolicy(3, Duration.ofMillis(1), 1, Duration.ofMillis(1)), d -> {
            throw new InterruptedException("stop");
        });
        AtomicInteger calls = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> r.call(() -> {
            calls.incrementAndGet();
            throw new IOException("x");
        }, RetrierTest::isIo));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, calls.get());
    }

    @Test
    void attemptReturnsValueOrEmpty() throws Exception {
        Optional<Integer> ok = retrier.attempt(() -> 7, RetrierTest::isIo);
        assertEquals(Optional.of(7), ok);
        Optional<Integer> none = retrier.attempt(() -> { throw new IOException("x"); }, RetrierTest::isIo);
        assertFalse(none.isPresent());
        assertThrows(IllegalArgumentException.class,
                () -> retrier.attempt(() -> { throw new IllegalArgumentException("no"); }, RetrierTest::isIo));
    }

    @Test
    void exhaustedExceptionIsChecked() {
        assertFalse(RuntimeException.class.isAssignableFrom(RetryExhaustedException.class));
        assertInstanceOf(Exception.class, new RetryExhaustedException(1, Duration.ZERO, null));
    }
}
