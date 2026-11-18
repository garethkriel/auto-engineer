package ae.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ae.outbox.domain.Booking;
import ae.outbox.domain.Show;
import ae.outbox.domain.SoldOutException;
import org.junit.jupiter.api.Test;

class DomainTest {
    @Test
    void reserveUpToCapacityThenSoldOut() {
        Show s = Show.of("s", 5).reserve(3).reserve(2);
        assertEquals(0, s.free());
        assertThrows(SoldOutException.class, () -> s.reserve(1));
    }

    @Test
    void reserveMoreThanFreeLeavesShowUnchanged() {
        Show s = Show.of("s", 5).reserve(4);
        assertThrows(SoldOutException.class, () -> s.reserve(2));
        assertEquals(4, s.reserved());
    }

    @Test
    void releaseRestoresSeatsAndRejectsOverRelease() {
        Show s = Show.of("s", 5).reserve(3).release(2);
        assertEquals(1, s.reserved());
        assertThrows(IllegalStateException.class, () -> s.release(2));
    }

    @Test
    void nonPositiveSeatCountsRejected() {
        Show s = Show.of("s", 5);
        assertThrows(IllegalArgumentException.class, () -> s.reserve(0));
        assertThrows(IllegalArgumentException.class, () -> s.release(-1));
        assertThrows(IllegalArgumentException.class, () -> new Show("s", 2, 3));
    }

    @Test
    void integerExtremesDoNotOverflow() {
        Show s = Show.of("s", Integer.MAX_VALUE).reserve(Integer.MAX_VALUE - 1);
        assertThrows(SoldOutException.class, () -> s.reserve(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE - 1, s.reserved());
    }

    @Test
    void bookingCancelsOnlyOnce() {
        Booking b = new Booking("b1", "s", "ann", 2, false).cancel();
        assertEquals(true, b.cancelled());
        assertThrows(IllegalStateException.class, b::cancel);
    }
}
