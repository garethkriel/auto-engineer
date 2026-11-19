package ae.build;

/** A build rule body. Dependencies are discovered by calling {@link Context#need}. */
@FunctionalInterface
public interface Task {
    /**
     * Computes the value of the rule.
     *
     * @return a non-null value
     * @throws Exception any failure; it is wrapped in a {@link BuildException}
     */
    String run(Context ctx) throws Exception;
}
