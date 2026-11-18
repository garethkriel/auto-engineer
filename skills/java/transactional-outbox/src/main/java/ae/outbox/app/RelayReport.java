package ae.outbox.app;

/** Outcome counts of one relay run. */
public record RelayReport(int delivered, int retried, int dead) {
}
