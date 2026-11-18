package ae.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.saga.adapter.InMemorySagaLog;
import ae.saga.app.SagaCoordinator;
import ae.saga.domain.LogEntry;
import ae.saga.domain.LogEntry.Kind;
import ae.saga.domain.Outcome;
import ae.saga.domain.SagaStatus;
import ae.saga.port.SagaLog;
import ae.saga.port.SagaStep;
import ae.saga.port.StepException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class SagaCoordinatorTest {

    /** Idempotent step: execute adds its name to the ledger, compensate removes it. */
    static class Step implements SagaStep {
        final String name;
        final Set<String> ledger;
        final List<String> calls;
        int execFailures;          // fail this many times before succeeding
        boolean execRetryable = true;
        int compFailures;
        int execCount;

        Step(String name, Set<String> ledger, List<String> calls) {
            this.name = name;
            this.ledger = ledger;
            this.calls = calls;
        }

        @Override public String name() { return name; }

        @Override public void execute(Map<String, String> ctx) throws StepException {
            calls.add("do:" + name);
            execCount++;
            if (execFailures > 0) {
                execFailures--;
                throw new StepException(name + " exec failed", execRetryable);
            }
            ledger.add(name);
        }

        @Override public void compensate(Map<String, String> ctx) throws StepException {
            calls.add("undo:" + name);
            if (compFailures > 0) {
                compFailures--;
                throw new StepException(name + " undo failed", true);
            }
            ledger.remove(name);
        }
    }

    /** Log that throws a simulated crash instead of storing the k-th append (1-based) once. */
    static class CrashingLog implements SagaLog {
        final SagaLog inner = new InMemorySagaLog();
        final int crashAt;
        int appends;

        CrashingLog(int crashAt) { this.crashAt = crashAt; }

        @Override public void append(String id, LogEntry e) {
            appends++;
            if (appends == crashAt) {
                throw new IllegalStateException("crash");
            }
            inner.append(id, e);
        }

        @Override public List<LogEntry> entries(String id) { return inner.entries(id); }
    }

    private final Set<String> ledger = new TreeSet<>();
    private final List<String> calls = new ArrayList<>();

    private List<SagaStep> three() {
        return List.of(new Step("pay", ledger, calls), new Step("reserve", ledger, calls),
                new Step("ship", ledger, calls));
    }

    @Test
    void allStepsSucceed() {
        SagaCoordinator c = new SagaCoordinator(new InMemorySagaLog(), 3);
        Outcome o = c.run("s1", three(), new HashMap<>());
        assertEquals(Outcome.completed(), o);
        assertEquals(List.of("do:pay", "do:reserve", "do:ship"), calls);
        assertEquals(Set.of("pay", "reserve", "ship"), ledger);
    }

    @Test
    void failureCompensatesCompletedStepsInReverseAndSkipsFailedStep() {
        List<SagaStep> steps = three();
        ((Step) steps.get(2)).execFailures = 1;
        ((Step) steps.get(2)).execRetryable = false;
        InMemorySagaLog log = new InMemorySagaLog();
        Outcome o = new SagaCoordinator(log, 3).run("s1", steps, new HashMap<>());
        assertEquals(new Outcome(SagaStatus.COMPENSATED, "ship", "ship exec failed"), o);
        assertEquals(List.of("do:pay", "do:reserve", "do:ship", "undo:reserve", "undo:pay"), calls);
        assertEquals(Set.of(), ledger);
        assertEquals(List.of(Kind.BEGIN, Kind.DONE, Kind.DONE, Kind.FAILED, Kind.COMPENSATED,
                Kind.COMPENSATED, Kind.ABORTED), log.entries("s1").stream().map(LogEntry::kind).toList());
    }

    @Test
    void retryableFailureIsRetriedUpToMaxAttempts() {
        List<SagaStep> steps = three();
        Step reserve = (Step) steps.get(1);
        reserve.execFailures = 2;
        Outcome o = new SagaCoordinator(new InMemorySagaLog(), 3).run("s1", steps, new HashMap<>());
        assertEquals(SagaStatus.COMPLETED, o.status());
        assertEquals(3, reserve.execCount);
    }

    @Test
    void exhaustedRetriesTriggerCompensation() {
        List<SagaStep> steps = three();
        Step reserve = (Step) steps.get(1);
        reserve.execFailures = 3;
        Outcome o = new SagaCoordinator(new InMemorySagaLog(), 3).run("s1", steps, new HashMap<>());
        assertEquals(SagaStatus.COMPENSATED, o.status());
        assertEquals(3, reserve.execCount);
        assertEquals(Set.of(), ledger);
    }

    @Test
    void nonRetryableFailureIsNotRetried() {
        List<SagaStep> steps = three();
        Step pay = (Step) steps.get(0);
        pay.execFailures = 5;
        pay.execRetryable = false;
        Outcome o = new SagaCoordinator(new InMemorySagaLog(), 4).run("s1", steps, new HashMap<>());
        assertEquals("pay", o.failedStep());
        assertEquals(1, pay.execCount);
        assertEquals(List.of("do:pay"), calls);
    }

    @Test
    void stuckCompensationReturnsStuckThenResumesToCompensated() {
        List<SagaStep> steps = three();
        ((Step) steps.get(2)).execFailures = 1;
        ((Step) steps.get(2)).execRetryable = false;
        ((Step) steps.get(1)).compFailures = 2;
        InMemorySagaLog log = new InMemorySagaLog();
        SagaCoordinator c = new SagaCoordinator(log, 2);
        Outcome first = c.run("s1", steps, new HashMap<>());
        assertEquals(SagaStatus.STUCK, first.status());
        assertEquals("ship", first.failedStep());
        assertEquals(Set.of("pay", "reserve"), ledger);   // nothing undone yet; pay not attempted
        assertTrue(log.entries("s1").stream().noneMatch(e -> e.kind() == Kind.ABORTED));

        Outcome second = c.run("s1", steps, new HashMap<>());
        assertEquals(SagaStatus.COMPENSATED, second.status());
        assertEquals(Set.of(), ledger);
        assertEquals("ship exec failed", second.reason());
    }

    @Test
    void terminalLogShortCircuitsWithoutTouchingSteps() {
        InMemorySagaLog log = new InMemorySagaLog();
        SagaCoordinator c = new SagaCoordinator(log, 2);
        c.run("s1", three(), new HashMap<>());
        calls.clear();
        assertEquals(Outcome.completed(), c.run("s1", three(), new HashMap<>()));
        assertEquals(List.of(), calls);
    }

    @Test
    void sagaIdsAreIndependent() {
        InMemorySagaLog log = new InMemorySagaLog();
        SagaCoordinator c = new SagaCoordinator(log, 2);
        c.run("a", three(), new HashMap<>());
        calls.clear();
        c.run("b", three(), new HashMap<>());
        assertEquals(3, calls.size());
    }

    @Test
    void contextIsSharedBetweenSteps() {
        SagaStep put = new SagaStep() {
            public String name() { return "put"; }
            public void execute(Map<String, String> ctx) { ctx.put("k", "v"); }
            public void compensate(Map<String, String> ctx) { }
        };
        Map<String, String> seen = new HashMap<>();
        SagaStep read = new SagaStep() {
            public String name() { return "read"; }
            public void execute(Map<String, String> ctx) { seen.put("k", ctx.get("k")); }
            public void compensate(Map<String, String> ctx) { }
        };
        new SagaCoordinator(new InMemorySagaLog(), 1).run("s", List.of(put, read), new HashMap<>());
        assertEquals("v", seen.get("k"));
    }

    @Test
    void validatesArguments() {
        SagaCoordinator c = new SagaCoordinator(new InMemorySagaLog(), 1);
        assertThrows(IllegalArgumentException.class, () -> new SagaCoordinator(new InMemorySagaLog(), 0));
        assertThrows(IllegalArgumentException.class, () -> c.run("s", List.of(), new HashMap<>()));
        assertThrows(IllegalArgumentException.class, () -> c.run("s", null, new HashMap<>()));
        assertThrows(NullPointerException.class, () -> c.run(null, three(), new HashMap<>()));
        Step a = new Step("x", ledger, calls);
        assertThrows(IllegalArgumentException.class, () -> c.run("s", List.of(a, new Step("x", ledger, calls)), new HashMap<>()));
    }

    /**
     * Crash at every possible log append, then resume with a healthy coordinator on the same log.
     * Oracle: the final outcome equals the uninterrupted run and the ledger shows the net effect.
     */
    @Test
    void crashAtEveryAppendThenResumeGivesSameOutcome() {
        for (boolean failShip : new boolean[] {false, true}) {
            // Uninterrupted baseline: success = 5 appends (BEGIN, 3 DONE, COMPLETE);
            // failure = BEGIN, DONE, DONE, FAILED, 2 COMPENSATED, ABORTED = 7 appends.
            int appends = failShip ? 7 : 5;
            for (int k = 1; k <= appends; k++) {
                ledger.clear();
                calls.clear();
                List<SagaStep> steps = three();
                if (failShip) {
                    ((Step) steps.get(2)).execFailures = 100;
                    ((Step) steps.get(2)).execRetryable = false;
                }
                CrashingLog log = new CrashingLog(k);
                String label = "failShip=" + failShip + " crashAt=" + k;
                assertThrows(IllegalStateException.class,
                        () -> new SagaCoordinator(log, 2).run("s", steps, new HashMap<>()), label);
                Outcome o = new SagaCoordinator(log, 2).run("s", steps, new HashMap<>());
                if (failShip) {
                    assertEquals(SagaStatus.COMPENSATED, o.status(), label);
                    assertEquals("ship", o.failedStep(), label);
                    assertEquals(Set.of(), ledger, label);
                } else {
                    assertEquals(Outcome.completed(), o, label);
                    assertEquals(Set.of("pay", "reserve", "ship"), ledger, label);
                }
                // A third run is a no-op on a terminal log.
                int before = calls.size();
                assertEquals(o.status(), new SagaCoordinator(log, 2).run("s", steps, new HashMap<>()).status(), label);
                assertEquals(before, calls.size(), label);
            }
        }
    }
}
