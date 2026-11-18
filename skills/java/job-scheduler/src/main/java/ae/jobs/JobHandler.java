package ae.jobs;

/** Work executed for one attempt of a job. Throwing any {@link Exception} fails the attempt. */
@FunctionalInterface
public interface JobHandler {
    void run(String payload) throws Exception;
}
