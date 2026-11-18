package ae.saga.port;

import ae.saga.domain.LogEntry;
import java.util.List;

/** Durable write-ahead log of saga progress. */
public interface SagaLog {
    /** Durably records an entry; if this throws the entry must not be visible. */
    void append(String sagaId, LogEntry entry);

    /** Entries for the saga in append order (empty if unknown). */
    List<LogEntry> entries(String sagaId);
}
