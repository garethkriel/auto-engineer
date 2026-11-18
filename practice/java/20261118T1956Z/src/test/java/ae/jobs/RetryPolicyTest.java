package ae.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {
    @Test
    void delaysDoubleThenCapAtMax() {
        RetryPolicy p = new RetryPolicy(10, 100, 500);
        assertEquals(100, p.delayAfter(1));
        assertEquals(200, p.delayAfter(2));
        assertEquals(400, p.delayAfter(3));
        assertEquals(500, p.delayAfter(4));
        assertEquals(500, p.delayAfter(5));
    }

    @Test
    void hugeAttemptNumbersAndHugeBasesDoNotOverflow() {
        RetryPolicy p = new RetryPolicy(100, Long.MAX_VALUE / 2, Long.MAX_VALUE);
        assertEquals(4_611_686_018_427_387_903L, p.delayAfter(1)); // Long.MAX_VALUE / 2
        assertEquals(9_223_372_036_854_775_806L, p.delayAfter(2)); // 2 * base still fits below the cap
        assertEquals(Long.MAX_VALUE, p.delayAfter(3));
        assertEquals(Long.MAX_VALUE, p.delayAfter(70));
        assertEquals(Long.MAX_VALUE, p.delayAfter(Integer.MAX_VALUE));
        RetryPolicy exact = new RetryPolicy(100, 1, 1L << 61);
        assertEquals(1L << 60, exact.delayAfter(61));
        assertEquals(1L << 61, exact.delayAfter(62));
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 5, 4));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 1, 1).delayAfter(0));
    }
}
