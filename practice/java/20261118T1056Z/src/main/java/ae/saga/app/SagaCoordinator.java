package ae.saga.app;

import ae.saga.domain.LogEntry;
import ae.saga.domain.LogEntry.Kind;
import ae.saga.domain.Outcome;
import ae.saga.domain.SagaStatus;
import ae.saga.port.SagaLog;
import ae.saga.port.SagaStep;
import ae.saga.port.StepException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs a list of steps as a saga, undoing completed steps in reverse order on failure.
 * All progress is derived from the {@link SagaLog}, so re-running a saga id resumes it.
 */
public class SagaCoordinator {
    private final SagaLog log;
    private final int maxAttempts;

    public SagaCoordinator(SagaLog log, int maxAttempts) {
        this.log = Objects.requireNonNull(log, "log");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
    }

    public Outcome run(String sagaId, List<SagaStep> steps, Map<String, String> ctx) {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(ctx, "ctx");
        validate(steps);

        List<LogEntry> entries = log.entries(sagaId);
        if (entries.isEmpty()) {
            log.append(sagaId, LogEntry.of(Kind.BEGIN));
            entries = log.entries(sagaId);
        }
        Set<String> done = new HashSet<>();
        Set<String> undone = new HashSet<>();
        String failedStep = null;
        String reason = null;
        for (LogEntry e : entries) {
            switch (e.kind()) {
                case COMPLETE: return Outcome.completed();
                case ABORTED: return new Outcome(SagaStatus.COMPENSATED, failedStep, reason);
                case DONE: done.add(e.step()); break;
                case COMPENSATED: undone.add(e.step()); break;
                case FAILED: failedStep = e.step(); reason = e.detail(); break;
                case BEGIN: break;
            }
        }

        if (failedStep == null) {
            for (SagaStep step : steps) {
                if (done.contains(step.name())) {
                    continue;
                }
                StepException failure = attempt(step, ctx, true);
                if (failure != null) {
                    failedStep = step.name();
                    reason = failure.getMessage();
                    log.append(sagaId, new LogEntry(Kind.FAILED, failedStep, reason));
                    break;
                }
                log.append(sagaId, LogEntry.of(Kind.DONE, step.name()));
                done.add(step.name());
            }
            if (failedStep == null) {
                log.append(sagaId, LogEntry.of(Kind.COMPLETE));
                return Outcome.completed();
            }
        }

        for (int i = steps.size() - 1; i >= 0; i--) {
            SagaStep step = steps.get(i);
            if (!done.contains(step.name()) || undone.contains(step.name())) {
                continue;
            }
            if (attempt(step, ctx, false) != null) {
                return new Outcome(SagaStatus.STUCK, failedStep, reason);
            }
            log.append(sagaId, LogEntry.of(Kind.COMPENSATED, step.name()));
        }
        log.append(sagaId, LogEntry.of(Kind.ABORTED));
        return new Outcome(SagaStatus.COMPENSATED, failedStep, reason);
    }

    /** Runs execute/compensate with retries; returns the last failure, or null on success. */
    private StepException attempt(SagaStep step, Map<String, String> ctx, boolean forward) {
        StepException last = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                if (forward) {
                    step.execute(ctx);
                } else {
                    step.compensate(ctx);
                }
                return null;
            } catch (StepException e) {
                last = e;
                // Compensation must keep trying; forward steps stop at the first non-retryable failure.
                if (forward && !e.isRetryable()) {
                    return e;
                }
            }
        }
        return last;
    }

    private static void validate(List<SagaStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        Set<String> names = new HashSet<>();
        for (SagaStep s : steps) {
            if (s == null || s.name() == null || !names.add(s.name())) {
                throw new IllegalArgumentException("steps must be non-null with unique names");
            }
        }
    }
}
