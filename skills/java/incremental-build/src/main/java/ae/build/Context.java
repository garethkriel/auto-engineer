package ae.build;

/** Handle given to a running {@link Task}; only valid while the task runs. */
public interface Context {
    /**
     * Builds {@code key} (recursively) and returns its value. The key is recorded as a dependency
     * of the running task, once, in first-need order.
     *
     * @throws BuildException if the dependency fails (a task may catch it)
     * @throws IllegalStateException if the task already finished
     */
    String need(String key);
}
