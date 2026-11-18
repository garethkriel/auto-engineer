package ae.saga.port;

import java.util.Map;

/** A unit of work with a compensating action. Both operations must be idempotent. */
public interface SagaStep {
    String name();

    void execute(Map<String, String> ctx) throws StepException;

    void compensate(Map<String, String> ctx) throws StepException;
}
