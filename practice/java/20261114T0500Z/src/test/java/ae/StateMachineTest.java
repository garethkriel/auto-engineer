package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class StateMachineTest {
    @Test
    void startsInNew() {
        assertEquals(OrderState.NEW, OrderState.newMachine().current());
    }

    @Test
    void happyPathRecordsHistory() {
        StateMachine<OrderState> m = OrderState.newMachine();
        m.move(OrderState.PAID);
        m.move(OrderState.SHIPPED);
        m.move(OrderState.DELIVERED);
        assertEquals(OrderState.DELIVERED, m.current());
        assertEquals(List.of(
                new StateMachine.Transition<>(OrderState.NEW, OrderState.PAID),
                new StateMachine.Transition<>(OrderState.PAID, OrderState.SHIPPED),
                new StateMachine.Transition<>(OrderState.SHIPPED, OrderState.DELIVERED)), m.history());
        assertTrue(m.isTerminal());
    }

    @Test
    void illegalMoveThrowsAndKeepsState() {
        StateMachine<OrderState> m = OrderState.newMachine();
        assertThrows(IllegalStateException.class, () -> m.move(OrderState.DELIVERED));
        assertEquals(OrderState.NEW, m.current());
        assertTrue(m.history().isEmpty());
        assertFalse(m.canMove(OrderState.SHIPPED));
        assertTrue(m.canMove(OrderState.CANCELLED));
    }

    @Test
    void cancelledIsTerminal() {
        StateMachine<OrderState> m = OrderState.newMachine();
        m.move(OrderState.CANCELLED);
        assertTrue(m.isTerminal());
        assertThrows(IllegalStateException.class, () -> m.move(OrderState.PAID));
    }

    @Test
    void shippedCannotBeCancelled() {
        StateMachine<OrderState> m = OrderState.newMachine();
        m.move(OrderState.PAID);
        m.move(OrderState.SHIPPED);
        assertFalse(m.canMove(OrderState.CANCELLED));
    }

    @Test
    void historyIsUnmodifiable() {
        StateMachine<OrderState> m = OrderState.newMachine();
        assertThrows(UnsupportedOperationException.class,
                () -> m.history().add(new StateMachine.Transition<>(OrderState.NEW, OrderState.PAID)));
    }

    @Test
    void transitionsAreDefensivelyCopied() {
        Set<OrderState> next = new HashSet<>(Set.of(OrderState.PAID));
        Map<OrderState, Set<OrderState>> t = new java.util.HashMap<>();
        t.put(OrderState.NEW, next);
        StateMachine<OrderState> m = new StateMachine<>(OrderState.class, OrderState.NEW, t);
        next.add(OrderState.DELIVERED);
        t.clear();
        assertFalse(m.canMove(OrderState.DELIVERED));
        assertTrue(m.canMove(OrderState.PAID));
    }

    @Test
    void rejectsNullArgs() {
        assertThrows(NullPointerException.class, () -> new StateMachine<>(OrderState.class, null, Map.of()));
        assertThrows(NullPointerException.class, () -> OrderState.newMachine().move(null));
    }

    @Test
    void parseIsCaseInsensitiveAndTrimmed() {
        assertEquals(Optional.of(OrderState.SHIPPED), OrderState.parse("  shipped "));
        assertEquals(Optional.empty(), OrderState.parse("nope"));
        assertEquals(Optional.empty(), OrderState.parse(null));
        assertEquals(Optional.empty(), OrderState.parse(""));
    }

    @Test
    void worksWithAnotherEnum() {
        enum Light { RED, GREEN }
        StateMachine<Light> m = new StateMachine<>(Light.class, Light.RED,
                Map.of(Light.RED, Set.of(Light.GREEN), Light.GREEN, Set.of(Light.RED)));
        m.move(Light.GREEN);
        m.move(Light.RED);
        assertEquals(2, m.history().size());
        assertFalse(m.isTerminal());
    }
}
