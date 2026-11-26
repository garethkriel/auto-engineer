package ae.book;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** One hand-built corrupt structure per rule of {@link OrderBook#checkInvariants()}; reflection builds the corruption. */
class InvariantCheckerTest {

    private static Object get(Object target, String field) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Object> byId(OrderBook b) throws ReflectiveOperationException {
        return (Map<Long, Object>) get(b, "byId");
    }

    @SuppressWarnings("unchecked")
    private static TreeMap<Long, LinkedHashMap<Long, Object>> side(OrderBook b, String name) throws ReflectiveOperationException {
        return (TreeMap<Long, LinkedHashMap<Long, Object>>) get(b, name);
    }

    private static String message(OrderBook b) {
        return assertThrows(IllegalStateException.class, b::checkInvariants).getMessage();
    }

    private static OrderBook oneBid(long display) {
        OrderBook b = new OrderBook();
        b.submit(Limit.of(1, "a", Side.BUY, 100, 10).iceberg(display));
        b.checkInvariants(); // control: the uncorrupted book is valid
        return b;
    }

    @Test
    void healthyBookPasses() {
        OrderBook b = oneBid(3);
        b.submit(Limit.of(2, "b", Side.SELL, 101, 4));
        b.checkInvariants();
    }

    @Test
    void emptyLevelDetected() throws Exception {
        OrderBook b = oneBid(0);
        side(b, "bids").put(90L, new LinkedHashMap<>());
        assertTrue(message(b).startsWith("empty level 90"), message(b));
    }

    @Test
    void misfiledOrderDetected() throws Exception {
        OrderBook b = oneBid(0);
        Object r = byId(b).get(1L);
        set(r, "total", 10L);
        LinkedHashMap<Long, Object> level = side(b, "bids").remove(100L);
        side(b, "bids").put(95L, level); // order says price 100 but sits at 95
        assertTrue(message(b).startsWith("misfiled 1"), message(b));
    }

    @Test
    void keyIdMismatchDetected() throws Exception {
        OrderBook b = oneBid(0);
        Object r = byId(b).get(1L);
        LinkedHashMap<Long, Object> level = side(b, "bids").get(100L);
        level.remove(1L);
        level.put(7L, r);
        assertTrue(message(b).startsWith("key/id mismatch 1"), message(b));
    }

    @Test
    void indexMismatchDetected() throws Exception {
        OrderBook b = oneBid(0);
        byId(b).remove(1L);
        assertTrue(message(b).startsWith("index mismatch 1"), message(b));
    }

    @Test
    void indexSizeDetected() throws Exception {
        OrderBook b = oneBid(0);
        byId(b).put(999L, byId(b).get(1L)); // extra index entry, per-order checks still pass
        assertTrue(message(b).startsWith("index size 2 vs 1"), message(b));
    }

    @Test
    void nonPositiveTotalDetected() throws Exception {
        OrderBook b = oneBid(0);
        set(byId(b).get(1L), "total", 0L);
        assertTrue(message(b).startsWith("non-positive total 1"), message(b));
    }

    @Test
    void visibleAboveTotalDetected() throws Exception {
        OrderBook b = oneBid(0);
        set(byId(b).get(1L), "visible", 11L);
        assertTrue(message(b).startsWith("bad visible 1"), message(b));
    }

    @Test
    void zeroVisibleDetected() throws Exception {
        OrderBook b = oneBid(3);
        set(byId(b).get(1L), "visible", 0L);
        assertTrue(message(b).startsWith("bad visible 1"), message(b));
    }

    @Test
    void plainOrderHidingQuantityDetected() throws Exception {
        OrderBook b = oneBid(0);
        set(byId(b).get(1L), "visible", 4L);
        assertTrue(message(b).startsWith("hidden plain 1"), message(b));
    }

    @Test
    void visibleOverDisplayDetected() throws Exception {
        OrderBook b = oneBid(3);
        set(byId(b).get(1L), "visible", 5L);
        assertTrue(message(b).startsWith("over display 1"), message(b));
    }

    @Test
    void crossedBookDetected() throws Exception {
        OrderBook b = oneBid(0);
        OrderBook other = new OrderBook();
        other.submit(Limit.of(50, "x", Side.SELL, 100, 2)); // equal price counts as crossed
        Object foreign = byId(other).get(50L);
        side(b, "asks").computeIfAbsent(100L, p -> new LinkedHashMap<>()).put(50L, foreign);
        byId(b).put(50L, foreign);
        assertTrue(message(b).startsWith("crossed book 100 >= 100"), message(b));
    }

    @Test
    void touchingButNotCrossedBookPasses() throws Exception {
        OrderBook b = oneBid(0);
        OrderBook other = new OrderBook();
        other.submit(Limit.of(50, "x", Side.SELL, 101, 2));
        Object foreign = byId(other).get(50L);
        side(b, "asks").computeIfAbsent(101L, p -> new LinkedHashMap<>()).put(50L, foreign);
        byId(b).put(50L, foreign);
        b.checkInvariants();
    }

    @Test
    void reflectionHelperSanity() throws Exception {
        // the corruption helpers can find the private class constructor shape (guards against silent no-op tests)
        Class<?> c = Class.forName("ae.book.OrderBook$Resting");
        Constructor<?>[] ctors = c.getDeclaredConstructors();
        assertEquals(1, ctors.length);
        assertEquals(6, ctors[0].getParameterCount());
    }
}
