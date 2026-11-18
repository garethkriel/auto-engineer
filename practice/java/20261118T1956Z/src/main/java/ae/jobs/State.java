package ae.jobs;

/** Lifecycle state of a job. SUCCEEDED, DEAD and CANCELLED are terminal. */
public enum State {
    PENDING, RUNNING, SUCCEEDED, DEAD, CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD || this == CANCELLED;
    }
}
