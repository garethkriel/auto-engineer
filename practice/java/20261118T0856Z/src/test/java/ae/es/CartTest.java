package ae.es;

import static org.junit.jupiter.api.Assertions.*;

import ae.es.domain.Cart;
import ae.es.domain.CartEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class CartTest {

    private static Cart replay(List<CartEvent> events) {
        Cart c = Cart.empty();
        for (CartEvent e : events) {
            c = c.apply(e);
        }
        return c;
    }

    @Test
    void openProducesEventAndOpensCart() {
        List<CartEvent> ev = Cart.empty().decideOpen();
        assertEquals(List.of(new CartEvent.CartOpened()), ev);
        assertTrue(replay(ev).isOpen());
    }

    @Test
    void cannotOpenTwice() {
        Cart c = replay(Cart.empty().decideOpen());
        assertThrows(IllegalStateException.class, c::decideOpen);
    }

    @Test
    void cannotAddBeforeOpen() {
        assertThrows(IllegalStateException.class, () -> Cart.empty().decideAdd("a", 1, 100));
    }

    @Test
    void addAccumulatesQuantityAndTotal() {
        Cart c = replay(Cart.empty().decideOpen());
        c = c.apply(c.decideAdd("a", 2, 150).get(0));
        c = c.apply(c.decideAdd("a", 3, 150).get(0));
        c = c.apply(c.decideAdd("b", 1, 1000).get(0));
        assertEquals(5, c.quantity("a"));
        assertEquals(1, c.quantity("b"));
        assertEquals(5 * 150 + 1000, c.totalCents());
    }

    @Test
    void rejectsNonPositiveQuantityAndPrice() {
        Cart c = replay(Cart.empty().decideOpen());
        assertThrows(IllegalArgumentException.class, () -> c.decideAdd("a", 0, 100));
        assertThrows(IllegalArgumentException.class, () -> c.decideAdd("a", -1, 100));
        assertThrows(IllegalArgumentException.class, () -> c.decideAdd("a", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> c.decideAdd("", 1, 100));
    }

    @Test
    void skuKeepsFirstUnitPrice() {
        Cart c = replay(Cart.empty().decideOpen());
        c = c.apply(c.decideAdd("a", 1, 100).get(0));
        Cart d = c;
        assertThrows(IllegalArgumentException.class, () -> d.decideAdd("a", 1, 101));
    }

    @Test
    void removeDropsWholeSkuAndFailsIfMissing() {
        Cart c = replay(Cart.empty().decideOpen());
        c = c.apply(c.decideAdd("a", 2, 100).get(0));
        Cart withA = c;
        assertThrows(IllegalArgumentException.class, () -> withA.decideRemove("zzz"));
        c = c.apply(c.decideRemove("a").get(0));
        assertEquals(0, c.quantity("a"));
        assertEquals(0, c.totalCents());
        // after removal the sku may come back at a new price
        c = c.apply(c.decideAdd("a", 1, 999).get(0));
        assertEquals(999, c.totalCents());
    }

    @Test
    void checkoutEmptyCartRejected() {
        Cart c = replay(Cart.empty().decideOpen());
        assertThrows(IllegalStateException.class, c::decideCheckout);
    }

    @Test
    void checkoutRecordsTotalAndLocksCart() {
        Cart c = replay(Cart.empty().decideOpen());
        c = c.apply(c.decideAdd("a", 2, 100).get(0));
        List<CartEvent> ev = c.decideCheckout();
        assertEquals(List.of(new CartEvent.CheckedOut(200)), ev);
        Cart done = c.apply(ev.get(0));
        assertTrue(done.isCheckedOut());
        assertFalse(done.isOpen());
        assertThrows(IllegalStateException.class, () -> done.decideAdd("a", 1, 100));
        assertThrows(IllegalStateException.class, () -> done.decideRemove("a"));
        assertThrows(IllegalStateException.class, done::decideCheckout);
    }

    @Test
    void overflowIsDetectedByDecideAndLeavesCartUnchanged() {
        long line = (long) Integer.MAX_VALUE * Integer.MAX_VALUE; // 4611686014132420609, two lines fit in a long
        Cart c = replay(Cart.empty().decideOpen());
        c = c.apply(c.decideAdd("a", Integer.MAX_VALUE, Integer.MAX_VALUE).get(0));
        c = c.apply(c.decideAdd("b", Integer.MAX_VALUE, Integer.MAX_VALUE).get(0));
        assertEquals(2 * line, c.totalCents());
        Cart two = c;
        assertThrows(ArithmeticException.class, () -> two.decideAdd("c", Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(2 * line, two.totalCents());
        assertEquals(0, two.quantity("c"));
    }

    @Test
    void applyDoesNotMutateReceiver() {
        Cart a = Cart.empty();
        Cart b = a.apply(new CartEvent.CartOpened());
        assertFalse(a.isOpen());
        assertTrue(b.isOpen());
    }
}
