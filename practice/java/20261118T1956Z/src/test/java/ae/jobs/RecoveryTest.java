package ae.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.jobs.Support.CrashingJournal;
import ae.jobs.Support.ManualClock;
import ae.jobs.Support.SimulatedCrash;
import ae.jobs.journal.FileJournal;
import ae.jobs.journal.InMemoryJournal;
import ae.jobs.journal.Journal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryTest {
    private final ManualClock clock = new ManualClock(0);

    @Test
    void restartKeepsTerminalStatesAndPendingRetriesWithoutRerunningFinishedJobs(@TempDir Path dir) {
        Path file = dir.resolve("jobs.log");
        Map<String, AtomicInteger> runs = new HashMap<>();
        JobHandler counting = p -> {
            int n = runs.computeIfAbsent(p, k -> new AtomicInteger()).incrementAndGet();
            if (p.equals("flaky") && n == 1) {
                throw new IllegalStateException("first try");
            }
        };
        RetryPolicy policy = new RetryPolicy(3, 100, 1000);
        JobScheduler first = new JobScheduler(new FileJournal(file), clock, policy, Map.of("w", counting));
        first.submit(JobSpec.of("ok", "w", "ok"));
        first.submit(JobSpec.of("flaky", "w", "flaky"));
        first.submit(JobSpec.of("child", "w", "child").after("flaky"));
        assertEquals(2, first.tick()); // ok succeeds, flaky fails once
        // process "restarts": a brand new scheduler over the same file
        JobScheduler second = new JobScheduler(new FileJournal(file), clock, policy, Map.of("w", counting));
        assertEquals(State.SUCCEEDED, second.status("ok").orElseThrow().state());
        assertEquals(new JobView("flaky", "w", State.PENDING, 1, 100, "IllegalStateException: first try"),
                second.status("flaky").orElseThrow());
        assertEquals(State.PENDING, second.status("child").orElseThrow().state());
        assertEquals(0, second.tick());
        clock.set(100);
        Support.drain(second, clock);
        assertEquals(Map.of("ok", State.SUCCEEDED, "flaky", State.SUCCEEDED, "child", State.SUCCEEDED),
                Support.states(second));
        assertEquals(1, runs.get("ok").get());
        assertEquals(2, runs.get("flaky").get());
        assertEquals(1, runs.get("child").get());
    }

    @Test
    void attemptInterruptedByCrashIsRecoveredAsAFailedAttemptAndRunsAgain() {
        InMemoryJournal base = new InMemoryJournal();
        AtomicInteger runs = new AtomicInteger();
        JobHandler h = p -> runs.incrementAndGet();
        RetryPolicy policy = new RetryPolicy(3, 100, 1000);
        clock.set(1000);
        // appends: 1 = Submitted, 2 = Started, 3 = Succeeded -> crash on the third
        JobScheduler crashing = new JobScheduler(new CrashingJournal(base, 2), clock, policy, Map.of("w", h));
        crashing.submit(JobSpec.of("a", "w", ""));
        assertThrows(SimulatedCrash.class, crashing::tick);
        assertEquals(1, runs.get()); // the handler really ran; only its success record was lost

        JobScheduler recovered = new JobScheduler(base, clock, policy, Map.of("w", h));
        assertEquals(new JobView("a", "w", State.PENDING, 1, 1100, "crashed during attempt 1"),
                recovered.status("a").orElseThrow());
        assertEquals(0, recovered.tick());
        clock.set(1100);
        assertEquals(1, recovered.tick());
        assertEquals(2, runs.get()); // at-least-once
        assertEquals(new JobView("a", "w", State.SUCCEEDED, 2, 1100, "crashed during attempt 1"),
                recovered.status("a").orElseThrow());
    }

    @Test
    void crashedAttemptOnTheLastTryKillsTheJobAndCancelsDependents() {
        InMemoryJournal base = new InMemoryJournal();
        JobHandler h = p -> { };
        RetryPolicy policy = new RetryPolicy(1, 100, 1000);
        // Submitted a, Submitted b, Started a, then crash before Succeeded
        JobScheduler crashing = new JobScheduler(new CrashingJournal(base, 3), clock, policy, Map.of("w", h));
        crashing.submit(JobSpec.of("a", "w", ""));
        crashing.submit(JobSpec.of("b", "w", "").after("a"));
        assertThrows(SimulatedCrash.class, crashing::tick);
        JobScheduler recovered = new JobScheduler(base, clock, policy, Map.of("w", h));
        assertEquals(Map.of("a", State.DEAD, "b", State.CANCELLED), Support.states(recovered));
        assertEquals("crashed during attempt 1", recovered.status("a").orElseThrow().lastError());
        // recovery is itself journaled, so a second restart sees the identical state and appends nothing
        int lines = base.readAll().size();
        JobScheduler again = new JobScheduler(base, clock, policy, Map.of("w", h));
        assertEquals(Support.states(recovered), Support.states(again));
        assertEquals(lines, base.readAll().size());
    }

    @Test
    void crashBetweenDeathAndCascadeIsRepairedOnRecovery() {
        InMemoryJournal base = new InMemoryJournal();
        JobHandler perm = p -> {
            throw new PermanentFailure("x");
        };
        RetryPolicy policy = new RetryPolicy(3, 100, 1000);
        // S a, S b, A a, D a, then crash before C b
        JobScheduler crashing = new JobScheduler(new CrashingJournal(base, 4), clock, policy, Map.of("w", perm));
        crashing.submit(JobSpec.of("a", "w", ""));
        crashing.submit(JobSpec.of("b", "w", "").after("a"));
        assertThrows(SimulatedCrash.class, crashing::tick);
        assertEquals(4, base.readAll().size());
        JobScheduler recovered = new JobScheduler(base, clock, policy, Map.of("w", perm));
        assertEquals(Map.of("a", State.DEAD, "b", State.CANCELLED), Support.states(recovered));
    }

    // ---- crash sweep -----------------------------------------------------------------------------

    private static final class World {
        final Map<String, Integer> runs = new HashMap<>();
        final Map<String, Integer> failuresLeft = new HashMap<>(Map.of("b", 1));

        Map<String, JobHandler> handlers() {
            JobHandler h = p -> {
                runs.merge(p, 1, Integer::sum);
                if (p.equals("d")) {
                    throw new PermanentFailure("d is broken");
                }
                int left = failuresLeft.getOrDefault(p, 0);
                if (left > 0) {
                    failuresLeft.put(p, left - 1);
                    throw new IllegalStateException("transient " + p);
                }
            };
            return Map.of("w", h);
        }
    }

    private static void submitAll(JobScheduler s) {
        submit(s, JobSpec.of("a", "w", "a"));
        submit(s, JobSpec.of("b", "w", "b").after("a"));
        submit(s, JobSpec.of("c", "w", "c").after("b"));
        submit(s, JobSpec.of("d", "w", "d"));
        submit(s, JobSpec.of("e", "w", "e").after("d"));
    }

    /** Idempotent client submit: a job that survived an earlier crash is skipped. */
    private static void submit(JobScheduler s, JobSpec spec) {
        if (s.status(spec.id()).isEmpty()) {
            s.submit(spec);
        }
    }

    private static final Map<String, State> EXPECTED = Map.of("a", State.SUCCEEDED, "b", State.SUCCEEDED,
            "c", State.SUCCEEDED, "d", State.DEAD, "e", State.CANCELLED);

    private int appendsOfAnUncrashedRun(RetryPolicy policy) {
        InMemoryJournal base = new InMemoryJournal();
        ManualClock c = new ManualClock(0);
        JobScheduler s = new JobScheduler(base, c, policy, new World().handlers());
        submitAll(s);
        Support.drain(s, c);
        assertEquals(EXPECTED, Support.states(s));
        return base.readAll().size();
    }

    @Test
    void crashAtEveryAppendIndexStillConvergesToTheNetEffect() {
        RetryPolicy policy = new RetryPolicy(3, 10, 100);
        int total = appendsOfAnUncrashedRun(policy);
        // 5 Submitted + a(Started,Succeeded) + b(Started,Failed,Started,Succeeded) + c(2) + d(Started,Died)
        // + e(Cancelled) = 5 + 2 + 4 + 2 + 2 + 1 = 16; pinned so design drift is noticed
        assertEquals(16, total);
        for (int crashAt = 0; crashAt <= total; crashAt++) {
            World world = new World();
            InMemoryJournal base = new InMemoryJournal();
            ManualClock c = new ManualClock(0);
            try {
                JobScheduler s = new JobScheduler(new CrashingJournal(base, crashAt), c, policy, world.handlers());
                submitAll(s);
                Support.drain(s, c);
            } catch (SimulatedCrash expected) {
                // fall through to recovery
            }
            JobScheduler recovered = new JobScheduler(base, c, policy, world.handlers());
            submitAll(recovered);
            Support.drain(recovered, c);
            String at = "crashAt=" + crashAt;
            assertEquals(EXPECTED, Support.states(recovered), at);
            assertTrue(world.runs.get("a") >= 1 && world.runs.get("b") >= 2 && world.runs.get("c") >= 1, at);
            assertTrue(world.runs.get("d") >= 1, at);
            assertTrue(!world.runs.containsKey("e"), at + ": e must never run");
            // ledger: no job has two Succeeded events or two terminal events in the journal
            Map<String, Integer> terminal = new HashMap<>();
            for (Event ev : Support.decodeAll(base)) {
                if (ev instanceof Event.Succeeded || ev instanceof Event.Died || ev instanceof Event.Cancelled) {
                    terminal.merge(ev.id(), 1, Integer::sum);
                }
            }
            assertEquals(Map.of("a", 1, "b", 1, "c", 1, "d", 1, "e", 1), terminal, at);
        }
    }

    @Test
    void crashDuringRecoveryItselfIsAlsoSurvivable() {
        // Each crash consumes an attempt of the job it interrupted, so allow enough attempts for b:
        // 1 real failure + up to 2 crashes + 1 success = 4 <= 6.
        RetryPolicy policy = new RetryPolicy(6, 10, 100);
        int total = appendsOfAnUncrashedRun(policy);
        for (int first = 1; first < total; first += 3) {
            for (int second = 0; second <= 3; second++) {
                World world = new World();
                InMemoryJournal base = new InMemoryJournal();
                ManualClock c = new ManualClock(0);
                try {
                    JobScheduler s = new JobScheduler(new CrashingJournal(base, first), c, policy, world.handlers());
                    submitAll(s);
                    Support.drain(s, c);
                } catch (SimulatedCrash expected) {
                    // first crash
                }
                try {
                    Journal flaky = new CrashingJournal(base, second);
                    JobScheduler s = new JobScheduler(flaky, c, policy, world.handlers());
                    submitAll(s);
                    Support.drain(s, c);
                } catch (SimulatedCrash expected) {
                    // second crash, during recovery or the continued run
                }
                JobScheduler last = new JobScheduler(base, c, policy, world.handlers());
                submitAll(last);
                Support.drain(last, c);
                assertEquals(EXPECTED, Support.states(last), "first=" + first + " second=" + second);
            }
        }
    }

    @Test
    void repeatedCrashesConsumeAttemptsSoAPoisonJobEventuallyDies() {
        InMemoryJournal base = new InMemoryJournal();
        JobHandler h = p -> { };
        RetryPolicy policy = new RetryPolicy(2, 10, 100);
        // crash on every Succeeded append: Submitted, Started pass (2 appends), Succeeded fails
        JobScheduler first = new JobScheduler(new CrashingJournal(base, 2), clock, policy, Map.of("w", h));
        first.submit(JobSpec.of("a", "w", ""));
        assertThrows(SimulatedCrash.class, first::tick);
        // recovery appends Failed (1), clock advances, attempt 2: Started (2), Succeeded fails
        JobScheduler second = new JobScheduler(new CrashingJournal(base, 2), clock, policy, Map.of("w", h));
        clock.set(10);
        assertEquals(State.PENDING, second.status("a").orElseThrow().state());
        assertThrows(SimulatedCrash.class, second::tick);
        JobScheduler third = new JobScheduler(base, clock, policy, Map.of("w", h));
        assertEquals(new JobView("a", "w", State.DEAD, 2, 10, "crashed during attempt 2"),
                third.status("a").orElseThrow());
    }

    @Test
    void journalReplayIsDeterministicAcrossRestarts() {
        InMemoryJournal base = new InMemoryJournal();
        RetryPolicy policy = new RetryPolicy(3, 10, 100);
        World w = new World();
        JobScheduler s = new JobScheduler(base, clock, policy, w.handlers());
        submitAll(s);
        Support.drain(s, clock);
        List<JobView> expected = s.all();
        for (int i = 0; i < 3; i++) {
            assertEquals(expected, new JobScheduler(base, clock, policy, w.handlers()).all());
        }
    }
}
