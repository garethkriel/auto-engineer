package retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {
    private static RetryPolicy policy() {
        return new RetryPolicy(5, Duration.ofMillis(100), 2, Duration.ofMillis(500));
    }

    @ParameterizedTest
    @CsvSource({"1,100", "2,200", "3,400", "4,500", "5,500", "1000,500", "2147483647,500"})
    void delayGrowsExponentiallyAndIsCapped(int attempt, long expectedMillis) {
        assertEquals(Duration.ofMillis(expectedMillis), policy().delayBefore(attempt));
    }

    @Test
    void multiplierOneKeepsDelayConstant() {
        RetryPolicy p = new RetryPolicy(3, Duration.ofSeconds(1), 1, Duration.ofSeconds(9));
        assertEquals(Duration.ofSeconds(1), p.delayBefore(100));
    }

    @Test
    void hugeMultiplierDoesNotOverflow() {
        RetryPolicy p = new RetryPolicy(3, Duration.ofSeconds(Long.MAX_VALUE / 4), Integer.MAX_VALUE,
                Duration.ofSeconds(Long.MAX_VALUE / 2));
        assertEquals(Duration.ofSeconds(Long.MAX_VALUE / 2), p.delayBefore(5));
    }

    @Test
    void zeroInitialDelayStaysZero() {
        RetryPolicy p = new RetryPolicy(2, Duration.ZERO, 3, Duration.ZERO);
        assertEquals(Duration.ZERO, p.delayBefore(4));
    }

    @Test
    void rejectsInvalidArguments() {
        Duration d = Duration.ofMillis(10);
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, d, 2, d));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, d.negated(), 2, d));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, d, 0, d));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, d, 2, Duration.ofMillis(9)));
        assertThrows(NullPointerException.class, () -> new RetryPolicy(1, null, 2, d));
        assertThrows(NullPointerException.class, () -> new RetryPolicy(1, d, 2, null));
        assertThrows(IllegalArgumentException.class, () -> policy().delayBefore(0));
    }
}
