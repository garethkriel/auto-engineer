package ae.outbox.app;

import java.time.Duration;

/** Test-only access to package-private relay internals. */
public final class RelayProbe {
    private RelayProbe() {
    }

    public static Duration backoff(OutboxRelay relay, int attempts) {
        return relay.backoff(attempts);
    }
}
