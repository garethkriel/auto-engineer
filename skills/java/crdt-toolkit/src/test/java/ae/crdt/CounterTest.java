package ae.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Random;
import org.junit.jupiter.api.Test;

class CounterTest {
    @Test
    void gCounterSumsReplicas() {
        GCounter g = GCounter.empty().increment("a", 3).increment("b", 4).increment("a", 2);
        assertEquals(9, g.value());
    }

    @Test
    void concurrentIncrementsAddUpAfterMerge() {
        GCounter a = GCounter.empty().increment("a", 5);
        GCounter b = GCounter.empty().increment("b", 7);
        assertEquals(12, a.merge(b).value());
        assertEquals(12, b.merge(a).value());
    }

    @Test
    void mergingStaleCopyDoesNotLoseOrDoubleCount() {
        GCounter old = GCounter.empty().increment("a", 2);
        GCounter newer = old.increment("a", 3);
        assertEquals(5, newer.merge(old).value());
        assertEquals(5, old.merge(newer).value());
        assertEquals(5, newer.merge(newer).value());
    }

    @Test
    void zeroIncrementIsNoOpAndBadArgsRejected() {
        GCounter g = GCounter.empty().increment("a", 1);
        assertSame(g, g.increment("a", 0));
        assertThrows(IllegalArgumentException.class, () -> g.increment("a", -1));
        assertThrows(IllegalArgumentException.class, () -> g.increment("", 1));
        assertThrows(NullPointerException.class, () -> g.increment(null, 1));
    }

    @Test
    void overflowIsDetected() {
        GCounter g = GCounter.empty().increment("a", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> g.increment("a", 1));
        GCounter two = g.increment("b", 1);
        assertThrows(ArithmeticException.class, two::value);
    }

    @Test
    void pnCounterGoesNegativeAndMerges() {
        PNCounter a = PNCounter.empty().increment("a", 2).decrement("a", 5);
        PNCounter b = PNCounter.empty().decrement("b", 4).increment("b", 1);
        assertEquals(-3, a.value());
        assertEquals(-3, b.value());
        assertEquals(-6, a.merge(b).value());
        assertEquals(a.merge(b), b.merge(a));
    }

    @Test
    void gCounterMergeLawsOnRandomStates() {
        Random rnd = new Random(42);
        for (int i = 0; i < 300; i++) {
            GCounter x = random(rnd);
            GCounter y = random(rnd);
            GCounter z = random(rnd);
            assertEquals(x.merge(y), y.merge(x), "commutative");
            assertEquals(x.merge(y).merge(z), x.merge(y.merge(z)), "associative");
            assertEquals(x, x.merge(x), "idempotent");
            assertEquals(true, x.merge(y).value() >= Math.max(x.value(), y.value()), "monotonic");
        }
    }

    private static GCounter random(Random rnd) {
        GCounter g = GCounter.empty();
        for (int k = rnd.nextInt(6); k > 0; k--) {
            g = g.increment("r" + rnd.nextInt(3), rnd.nextInt(10));
        }
        return g;
    }
}
