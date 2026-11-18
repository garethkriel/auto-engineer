package ae.raft;

import java.util.Objects;

/**
 * One replicated log entry.
 *
 * @param term    term of the leader that created the entry
 * @param command opaque state-machine command; {@link #NOOP} marks the leader's no-op entry
 */
public record LogEntry(int term, String command) {
    /** Command string used for the no-op entry a new leader appends. */
    public static final String NOOP = "";

    public LogEntry {
        Objects.requireNonNull(command, "command");
        if (term < 1) {
            throw new IllegalArgumentException("term must be >= 1: " + term);
        }
    }

    /** True for the leader no-op, which is never exposed to the state machine. */
    public boolean isNoop() {
        return command.equals(NOOP);
    }
}
