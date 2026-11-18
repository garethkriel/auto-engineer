package ae.saga.domain;

import java.util.Objects;

/** One write-ahead log record. {@code step} is null for BEGIN/COMPLETE/ABORTED; {@code detail} is set only for FAILED. */
public record LogEntry(Kind kind, String step, String detail) {
    public enum Kind { BEGIN, DONE, FAILED, COMPENSATED, COMPLETE, ABORTED }

    public LogEntry {
        Objects.requireNonNull(kind, "kind");
    }

    public static LogEntry of(Kind kind) { return new LogEntry(kind, null, null); }
    public static LogEntry of(Kind kind, String step) { return new LogEntry(kind, step, null); }
}
