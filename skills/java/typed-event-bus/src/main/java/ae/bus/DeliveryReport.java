package ae.bus;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of one publish.
 *
 * @param delivered number of handlers that completed normally
 * @param failures  exceptions thrown by handlers, in delivery order
 */
public record DeliveryReport(int delivered, List<Throwable> failures) {
    public DeliveryReport {
        Objects.requireNonNull(failures, "failures");
        failures = List.copyOf(failures);
    }

    /** True when no handler failed. */
    public boolean ok() {
        return failures.isEmpty();
    }
}
