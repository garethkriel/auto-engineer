package ae.jobs;

/** Read-only snapshot of one job. {@code lastError} is empty when nothing has failed or been cancelled. */
public record JobView(String id, String type, State state, int attempts, long runAt, String lastError) {
}
