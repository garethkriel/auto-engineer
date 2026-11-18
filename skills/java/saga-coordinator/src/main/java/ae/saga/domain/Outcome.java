package ae.saga.domain;

/**
 * Result of a saga run.
 *
 * @param status     final status
 * @param failedStep name of the step whose failure triggered compensation, or null
 * @param reason     failure message, or null
 */
public record Outcome(SagaStatus status, String failedStep, String reason) {
    public static Outcome completed() { return new Outcome(SagaStatus.COMPLETED, null, null); }
}
