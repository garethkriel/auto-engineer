package ae.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.jobs.Support.ManualClock;
import ae.jobs.journal.InMemoryJournal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SchedulerTest {
    private final ManualClock clock = new ManualClock(0);
    private final List<String> ran = new ArrayList<>();

    private JobScheduler scheduler(RetryPolicy policy, Map<String, JobHandler> handlers) {
        return new JobScheduler(new InMemoryJournal(), clock, policy, handlers);
    }

    private JobHandler record() {
        return payload -> ran.add(payload);
    }

    @Test
    void runsASubmittedJobOnce() {
        JobScheduler s = scheduler(new RetryPolicy(3, 100, 1000), Map.of("echo", record()));
        s.submit(JobSpec.of("j1", "echo", "hello"));
        assertEquals(1, s.tick());
        assertEquals(List.of("hello"), ran);
        assertEquals(new JobView("j1", "echo", State.SUCCEEDED, 1, 0, ""), s.status("j1").orElseThrow());
        assertEquals(0, s.tick());
        assertEquals(List.of("hello"), ran);
        assertTrue(s.isIdle());
    }

    @Test
    void retriesFollowTheExactBackoffSchedule() {
        AtomicInteger calls = new AtomicInteger();
        JobHandler flaky = p -> {
            if (calls.incrementAndGet() <= 3) {
                throw new IllegalStateException("fail " + calls.get());
            }
        };
        JobScheduler s = scheduler(new RetryPolicy(5, 100, 1000), Map.of("flaky", flaky));
        s.submit(JobSpec.of("j", "flaky", ""));

        assertEquals(1, s.tick()); // t=0, attempt 1 fails, retry at 0+100
        assertEquals(new JobView("j", "flaky", State.PENDING, 1, 100, "IllegalStateException: fail 1"),
                s.status("j").orElseThrow());
        clock.set(99);
        assertEquals(0, s.tick());
        clock.set(100);
        assertEquals(1, s.tick()); // attempt 2 fails, retry at 100+200
        assertEquals(300, s.status("j").orElseThrow().runAt());
        clock.set(299);
        assertEquals(0, s.tick());
        clock.set(300);
        assertEquals(1, s.tick()); // attempt 3 fails, retry at 300+400
        assertEquals(700, s.status("j").orElseThrow().runAt());
        clock.set(700);
        assertEquals(1, s.tick()); // attempt 4 succeeds
        JobView v = s.status("j").orElseThrow();
        assertEquals(State.SUCCEEDED, v.state());
        assertEquals(4, v.attempts());
        assertEquals(4, calls.get());
    }

    @Test
    void diesAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        JobHandler bad = p -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        };
        JobScheduler s = scheduler(new RetryPolicy(3, 10, 100), Map.of("bad", bad));
        s.submit(JobSpec.of("j", "bad", ""));
        Support.drain(s, clock);
        assertEquals(3, calls.get());
        assertEquals(new JobView("j", "bad", State.DEAD, 3, 30, "IllegalStateException: boom"),
                s.status("j").orElseThrow());
        assertEquals(30, clock.now()); // attempts at 0, 10, 30
    }

    @Test
    void permanentFailureSkipsRetries() {
        JobHandler perm = p -> {
            throw new PermanentFailure("bad input");
        };
        JobScheduler s = scheduler(new RetryPolicy(5, 10, 100), Map.of("perm", perm));
        s.submit(JobSpec.of("j", "perm", ""));
        assertEquals(1, s.tick());
        JobView v = s.status("j").orElseThrow();
        assertEquals(State.DEAD, v.state());
        assertEquals(1, v.attempts());
        assertEquals("PermanentFailure: bad input", v.lastError());
    }

    @Test
    void dependenciesGateExecutionAndTiesBreakBySubmissionOrder() {
        JobScheduler s = scheduler(new RetryPolicy(3, 10, 100), Map.of("echo", record()));
        s.submit(JobSpec.of("a", "echo", "a"));
        s.submit(JobSpec.of("b", "echo", "b"));
        s.submit(JobSpec.of("c", "echo", "c").after("a", "b"));
        s.submit(JobSpec.of("x", "echo", "x"));
        assertEquals(4, s.tick());
        assertEquals(List.of("a", "b", "c", "x"), ran);
    }

    @Test
    void dueTimeOrdersBeforeSubmissionOrder() {
        JobScheduler s = scheduler(new RetryPolicy(3, 10, 100), Map.of("echo", record()));
        s.submit(JobSpec.of("p", "echo", "p").at(50));
        s.submit(JobSpec.of("q", "echo", "q").at(10));
        s.submit(JobSpec.of("r", "echo", "r").at(10));
        clock.set(100);
        assertEquals(3, s.tick());
        assertEquals(List.of("q", "r", "p"), ran);
    }

    @Test
    void jobsWithFutureRunAtWaitAndNextWakeupReportsThem() {
        JobScheduler s = scheduler(new RetryPolicy(3, 10, 100), Map.of("echo", record()));
        assertEquals(Optional.empty(), s.nextWakeup());
        s.submit(JobSpec.of("late", "echo", "late").at(500));
        s.submit(JobSpec.of("after-late", "echo", "x").at(5).after("late"));
        assertEquals(0, s.tick());
        assertEquals(Optional.of(500L), s.nextWakeup()); // the dependent is not ready, so it is not counted
        clock.set(500);
        assertEquals(2, s.tick());
        assertEquals(List.of("late", "x"), ran);
        assertEquals(Optional.empty(), s.nextWakeup());
    }

    @Test
    void deadJobCancelsItsTransitiveDependentsButNotUnrelatedJobs() {
        JobHandler perm = p -> {
            throw new PermanentFailure("nope");
        };
        JobScheduler s = scheduler(new RetryPolicy(3, 10, 100), Map.of("perm", perm, "echo", record()));
        s.submit(JobSpec.of("a", "perm", ""));
        s.submit(JobSpec.of("b", "echo", "b").after("a"));
        s.submit(JobSpec.of("c", "echo", "c").after("b"));
        s.submit(JobSpec.of("d", "echo", "d"));
        s.submit(JobSpec.of("e", "echo", "e").after("a", "d"));
        assertEquals(2, s.tick()); // a (dies) and d
        assertEquals(List.of("d"), ran);
        assertEquals(Map.of("a", State.DEAD, "b", State.CANCELLED, "c", State.CANCELLED, "d", State.SUCCEEDED,
                "e", State.CANCELLED), Support.states(s));
        assertEquals("dependency a is DEAD", s.status("b").orElseThrow().lastError());
        assertEquals("dependency b is CANCELLED", s.status("c").orElseThrow().lastError());
        assertEquals("dependency a is DEAD", s.status("e").orElseThrow().lastError());
        s.submit(JobSpec.of("f", "echo", "f").after("c"));
        assertEquals(State.CANCELLED, s.status("f").orElseThrow().state());
        assertTrue(s.isIdle());
    }

    @Test
    void cancelOnlyAffectsPendingJobsAndCascades() {
        JobScheduler s = scheduler(new RetryPolicy(3, 10, 100), Map.of("echo", record()));
        s.submit(JobSpec.of("a", "echo", "a"));
        s.submit(JobSpec.of("b", "echo", "b").after("a"));
        s.cancel("a");
        assertEquals(State.CANCELLED, s.status("a").orElseThrow().state());
        assertEquals(State.CANCELLED, s.status("b").orElseThrow().state());
        assertThrows(IllegalStateException.class, () -> s.cancel("a"));
        assertThrows(IllegalArgumentException.class, () -> s.cancel("zzz"));
        assertEquals(0, s.tick());
        assertEquals(List.of(), ran);
    }

    @Test
    void submitValidation() {
        JobScheduler s = scheduler(new RetryPolicy(3, 10, 100), Map.of("echo", record()));
        s.submit(JobSpec.of("a", "echo", ""));
        assertThrows(IllegalArgumentException.class, () -> s.submit(JobSpec.of("a", "echo", "")));
        assertThrows(IllegalArgumentException.class, () -> s.submit(JobSpec.of("b", "echo", "").after("ghost")));
        assertThrows(IllegalArgumentException.class, () -> JobSpec.of(" ", "echo", ""));
        assertThrows(IllegalArgumentException.class, () -> JobSpec.of("b", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new JobSpec("b", "echo", "", Set.of(" "), 0));
        assertEquals(1, s.all().size()); // failed submits left no trace
    }

    @Test
    void missingHandlerFailsTheAttemptInsteadOfCrashingTheTick() {
        JobScheduler s = scheduler(new RetryPolicy(2, 10, 100), Map.of());
        s.submit(JobSpec.of("a", "unknown", ""));
        assertEquals(1, s.tick());
        assertEquals("IllegalStateException: no handler for type unknown", s.status("a").orElseThrow().lastError());
        Support.drain(s, clock);
        assertEquals(State.DEAD, s.status("a").orElseThrow().state());
    }

    @Test
    void handlersRunOutsideTheLockSoTheyMaySubmitFromAnotherThread() throws Exception {
        JobScheduler[] holder = new JobScheduler[1];
        List<String> order = new ArrayList<>();
        JobHandler parent = p -> {
            Thread t = new Thread(() -> holder[0].submit(JobSpec.of("child", "child", "")));
            t.start();
            t.join(5_000);
            if (t.isAlive()) {
                throw new IllegalStateException("handler holds the scheduler lock");
            }
            order.add("parent");
        };
        JobHandler child = p -> order.add("child");
        holder[0] = scheduler(new RetryPolicy(2, 10, 100), Map.of("parent", parent, "child", child));
        holder[0].submit(JobSpec.of("p", "parent", ""));
        assertEquals(2, holder[0].tick());
        assertEquals(List.of("parent", "child"), order);
        assertFalse(holder[0].status("child").isEmpty());
    }

    @Test
    void interruptedHandlerStopsTheTickAndKeepsTheInterruptFlag() {
        JobHandler interrupted = p -> {
            throw new InterruptedException("stop");
        };
        JobScheduler s = scheduler(new RetryPolicy(2, 10, 100), Map.of("i", interrupted, "echo", record()));
        s.submit(JobSpec.of("a", "i", ""));
        s.submit(JobSpec.of("b", "echo", "b"));
        try {
            assertEquals(1, s.tick());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            assertTrue(Thread.interrupted()); // clear the flag for later tests
        }
        assertEquals(State.RUNNING, s.status("a").orElseThrow().state());
        assertEquals(List.of(), ran);
    }
}
