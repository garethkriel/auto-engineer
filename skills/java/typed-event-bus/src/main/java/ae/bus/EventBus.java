package ae.bus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Small single-threaded typed event bus. */
public final class EventBus {
    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
            int.class, Integer.class, long.class, Long.class, double.class, Double.class,
            float.class, Float.class, boolean.class, Boolean.class, char.class, Character.class,
            byte.class, Byte.class, short.class, Short.class);

    private record Entry(Class<?> type, Consumer<Object> handler, Priority priority, Subscription sub) { }

    private final List<Entry> entries = new ArrayList<>();

    public <E> Subscription subscribe(Class<E> type, Consumer<? super E> handler) {
        return subscribe(type, handler, Priority.NORMAL);
    }

    public <E> Subscription subscribe(Class<E> type, Consumer<? super E> handler, Priority priority) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(priority, "priority");
        Class<?> token = BOXES.getOrDefault(type, type);
        Subscription sub = new Subscription();
        // Safe: the token is only used to filter events, so handler sees only E (or its box).
        @SuppressWarnings("unchecked")
        Consumer<Object> h = (Consumer<Object>) handler;
        entries.add(new Entry(token, h, priority, sub));
        return sub;
    }

    public DeliveryReport publish(Object event) {
        Objects.requireNonNull(event, "event");
        List<Entry> snapshot = new ArrayList<>();
        entries.removeIf(e -> !e.sub().isActive());
        for (Entry e : entries) {
            if (e.type().isAssignableFrom(event.getClass())) {
                snapshot.add(e);
            }
        }
        // List.sort is stable, so subscription order is kept within a priority.
        snapshot.sort((a, b) -> a.priority().compareTo(b.priority()));
        int delivered = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Entry e : snapshot) {
            try {
                e.handler().accept(event);
                delivered++;
            } catch (RuntimeException ex) {
                failures.add(ex);
            }
        }
        return new DeliveryReport(delivered, failures);
    }
}
