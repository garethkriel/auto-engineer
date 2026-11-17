package conc;

import java.util.List;

/** Reported by {@link Parallel#firstSuccess} when every supplier failed. */
public class AllFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient List<Throwable> causes;

    public AllFailedException(List<Throwable> causes) {
        super(causes.size() + " task(s) failed", causes.isEmpty() ? null : causes.get(0));
        this.causes = List.copyOf(causes);
    }

    /** All failures, in the order they were recorded. */
    public List<Throwable> causes() {
        return causes;
    }
}
