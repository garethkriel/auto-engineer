package ae.jobs;

/** Thrown by a handler to say "do not retry": the job dies immediately. */
public final class PermanentFailure extends Exception {
    private static final long serialVersionUID = 1L;

    public PermanentFailure(String message) {
        super(message);
    }
}
