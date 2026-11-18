package ae.saga.adapter;

import ae.saga.domain.LogEntry;
import ae.saga.port.SagaLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Thread-safe in-memory {@link SagaLog}. */
public class InMemorySagaLog implements SagaLog {
    private final Map<String, List<LogEntry>> logs = new HashMap<>();

    @Override
    public synchronized void append(String sagaId, LogEntry entry) {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(entry, "entry");
        logs.computeIfAbsent(sagaId, k -> new ArrayList<>()).add(entry);
    }

    @Override
    public synchronized List<LogEntry> entries(String sagaId) {
        return List.copyOf(logs.getOrDefault(sagaId, List.of()));
    }
}
