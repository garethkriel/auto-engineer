package ae.bus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventBusTest {
    @Test
    void deliversOnlyToMatchingTypes() {
        EventBus bus = new EventBus();
        List<String> got = new ArrayList<>();
        bus.subscribe(String.class, got::add);
        bus.subscribe(Integer.class, i -> got.add("int" + i));
        DeliveryReport r = bus.publish("a");
        bus.publish(7);
        assertEquals(List.of("a", "int7"), got);
        assertEquals(1, r.delivered());
        assertTrue(r.ok());
    }

    @Test
    void superTypeSubscriberReceivesSubclassEvents() {
        EventBus bus = new EventBus();
        List<Object> got = new ArrayList<>();
        bus.subscribe(Number.class, got::add);
        bus.subscribe(Object.class, o -> got.add("obj"));
        bus.publish(3);
        assertEquals(List.of(3, "obj"), got);
    }

    @Test
    void ordersByPriorityThenSubscription() {
        EventBus bus = new EventBus();
        List<String> got = new ArrayList<>();
        bus.subscribe(String.class, s -> got.add("low"), Priority.LOW);
        bus.subscribe(String.class, s -> got.add("n1"));
        bus.subscribe(String.class, s -> got.add("high"), Priority.HIGH);
        bus.subscribe(String.class, s -> got.add("n2"), Priority.NORMAL);
        bus.publish("x");
        assertEquals(List.of("high", "n1", "n2", "low"), got);
    }

    @Test
    void cancelStopsDeliveryAndIsIdempotent() {
        EventBus bus = new EventBus();
        List<String> got = new ArrayList<>();
        Subscription s = bus.subscribe(String.class, got::add);
        assertTrue(s.isActive());
        s.cancel();
        s.cancel();
        assertFalse(s.isActive());
        assertEquals(0, bus.publish("a").delivered());
        assertTrue(got.isEmpty());
    }

    @Test
    void failingHandlerDoesNotStopOthers() {
        EventBus bus = new EventBus();
        List<String> got = new ArrayList<>();
        RuntimeException boom = new IllegalStateException("boom");
        bus.subscribe(String.class, s -> { throw boom; }, Priority.HIGH);
        bus.subscribe(String.class, got::add);
        DeliveryReport r = bus.publish("a");
        assertEquals(List.of("a"), got);
        assertEquals(1, r.delivered());
        assertEquals(List.of(boom), r.failures());
        assertFalse(r.ok());
    }

    @Test
    void subscribeDuringPublishAffectsOnlyLaterPublishes() {
        EventBus bus = new EventBus();
        List<String> got = new ArrayList<>();
        bus.subscribe(String.class, s -> bus.subscribe(String.class, t -> got.add("late:" + t)));
        bus.publish("1");
        assertTrue(got.isEmpty());
        bus.publish("2");
        assertEquals(List.of("late:2"), got);
    }

    @Test
    void cancelDuringPublishDoesNotAffectCurrentSnapshot() {
        EventBus bus = new EventBus();
        List<String> got = new ArrayList<>();
        Subscription[] second = new Subscription[1];
        bus.subscribe(String.class, s -> second[0].cancel(), Priority.HIGH);
        second[0] = bus.subscribe(String.class, got::add);
        bus.publish("a");
        assertEquals(List.of("a"), got);
        bus.publish("b");
        assertEquals(List.of("a"), got);
    }

    @Test
    void primitiveTokenMatchesBoxedEvent() {
        EventBus bus = new EventBus();
        List<Integer> got = new ArrayList<>();
        bus.subscribe(int.class, got::add);
        bus.publish(5);
        assertEquals(List.of(5), got);
    }

    @Test
    void wildcardConsumerAcceptsSuperTypeHandler() {
        EventBus bus = new EventBus();
        List<Object> got = new ArrayList<>();
        java.util.function.Consumer<Object> any = got::add;
        bus.subscribe(String.class, any);
        bus.publish("z");
        assertEquals(List.of("z"), got);
    }

    @Test
    void nullArgumentsRejected() {
        EventBus bus = new EventBus();
        assertThrows(NullPointerException.class, () -> bus.subscribe(null, o -> { }));
        assertThrows(NullPointerException.class, () -> bus.subscribe(String.class, null));
        assertThrows(NullPointerException.class, () -> bus.subscribe(String.class, s -> { }, null));
        assertThrows(NullPointerException.class, () -> bus.publish(null));
    }

    @Test
    void reportCopiesFailuresDefensively() {
        List<Throwable> src = new ArrayList<>();
        DeliveryReport r = new DeliveryReport(0, src);
        src.add(new RuntimeException());
        assertTrue(r.failures().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> r.failures().add(new RuntimeException()));
    }

    @Test
    void noSubscribersDeliversZero() {
        assertEquals(0, new EventBus().publish("x").delivered());
    }
}
