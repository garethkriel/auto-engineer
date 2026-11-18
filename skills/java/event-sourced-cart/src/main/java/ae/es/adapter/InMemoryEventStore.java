package ae.es.adapter;

import ae.es.domain.CartEvent;
import ae.es.port.ConcurrencyException;
import ae.es.port.EventStore;
import ae.es.port.StoredEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Thread-safe in-memory {@link EventStore}; every operation holds one lock. */
public final class InMemoryEventStore implements EventStore {
    private final Object lock = new Object();
    private final List<StoredEvent> log = new ArrayList<>();
    private final Map<String, List<StoredEvent>> streams = new HashMap<>();

    @Override
    public void append(String streamId, long expectedVersion, List<? extends CartEvent> events) {
        Objects.requireNonNull(streamId, "streamId");
        List<CartEvent> batch = new ArrayList<>(Objects.requireNonNull(events, "events"));
        for (CartEvent e : batch) {
            Objects.requireNonNull(e, "event");
        }
        synchronized (lock) {
            List<StoredEvent> stream = streams.getOrDefault(streamId, List.of());
            long actual = stream.size();
            if (actual != expectedVersion) {
                throw new ConcurrencyException(streamId, expectedVersion, actual);
            }
            if (batch.isEmpty()) {
                return;
            }
            List<StoredEvent> added = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                added.add(new StoredEvent(log.size() + i + 1L, streamId, actual + i + 1L, batch.get(i)));
            }
            log.addAll(added);
            streams.computeIfAbsent(streamId, k -> new ArrayList<>()).addAll(added);
        }
    }

    @Override
    public List<StoredEvent> read(String streamId) {
        synchronized (lock) {
            return List.copyOf(streams.getOrDefault(streamId, List.of()));
        }
    }

    @Override
    public List<StoredEvent> readAll(long afterPosition) {
        if (afterPosition < 0) {
            throw new IllegalArgumentException("afterPosition must be >= 0");
        }
        synchronized (lock) {
            if (afterPosition >= log.size()) {
                return List.of();
            }
            return List.copyOf(log.subList((int) afterPosition, log.size()));
        }
    }
}
