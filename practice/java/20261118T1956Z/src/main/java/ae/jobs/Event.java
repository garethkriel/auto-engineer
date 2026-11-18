package ae.jobs;

import java.util.List;

/** Facts recorded in the journal. State is a pure fold over these events. */
sealed interface Event {
    String id();

    record Submitted(String id, String type, String payload, List<String> dependsOn, long runAt) implements Event {
    }

    record Started(String id, int attempt) implements Event {
    }

    record Succeeded(String id) implements Event {
    }

    /** Attempt failed; the job returns to PENDING and becomes due at {@code nextRunAt}. */
    record Failed(String id, int attempt, String error, long nextRunAt) implements Event {
    }

    record Died(String id, int attempt, String error) implements Event {
    }

    record Cancelled(String id, String reason) implements Event {
    }
}
