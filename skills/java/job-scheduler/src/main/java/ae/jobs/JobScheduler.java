package ae.jobs;

import ae.jobs.journal.CorruptJournalException;
import ae.jobs.journal.Journal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable single-runner job scheduler.
 *
 * <p>Every state change is an {@link Event} appended to the {@link Journal} <em>before</em> it is applied
 * in memory, so a crash at any point leaves a journal from which {@link #JobScheduler recovery} rebuilds
 * the exact state. Execution is at-least-once: an attempt that was started but never finished is recovered
 * as a failed attempt. User handlers run outside the lock. If an append throws, the scheduler instance must be
 * discarded and rebuilt from the journal.
 */
public final class JobScheduler {
    private static final class Entry {
        final Event.Submitted spec;
        final long seq;
        State state = State.PENDING;
        int attempts;
        long runAt;
        String lastError = "";

        Entry(Event.Submitted spec, long seq) {
            this.spec = spec;
            this.seq = seq;
            this.runAt = spec.runAt();
        }
    }

    private final Journal journal;
    private final TimeSource time;
    private final RetryPolicy policy;
    private final Map<String, JobHandler> handlers;
    private final Map<String, Entry> jobs = new LinkedHashMap<>();

    /**
     * Opens the scheduler, replaying the journal. Attempts left RUNNING by a crash are closed as failed
     * attempts and dependents of dead jobs are cancelled.
     *
     * @throws CorruptJournalException if the journal cannot be replayed
     */
    public JobScheduler(Journal journal, TimeSource time, RetryPolicy policy, Map<String, JobHandler> handlers) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.time = Objects.requireNonNull(time, "time");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.handlers = Map.copyOf(handlers);
        for (String line : journal.readAll()) {
            apply(EventCodec.decode(line));
        }
        synchronized (this) {
            for (Entry e : new ArrayList<>(jobs.values())) {
                if (e.state == State.RUNNING) {
                    finishFailed(e, "crashed during attempt " + e.attempts, false);
                }
            }
            settleBlocked();
        }
    }

    /** Registers a new job. Dependencies must already exist, so cycles are impossible. */
    public synchronized void submit(JobSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (jobs.containsKey(spec.id())) {
            throw new IllegalArgumentException("duplicate job id: " + spec.id());
        }
        for (String dep : spec.dependsOn()) {
            if (!jobs.containsKey(dep)) {
                throw new IllegalArgumentException("unknown dependency: " + dep);
            }
        }
        commit(new Event.Submitted(spec.id(), spec.type(), spec.payload(), List.copyOf(spec.dependsOn()),
                spec.runAt()));
        settleBlocked();
    }

    /** Cancels a job that has not started running (PENDING), and its dependents. */
    public synchronized void cancel(String id) {
        Entry e = jobs.get(id);
        if (e == null) {
            throw new IllegalArgumentException("unknown job: " + id);
        }
        if (e.state != State.PENDING) {
            throw new IllegalStateException("job " + id + " is " + e.state + ", only PENDING jobs can be cancelled");
        }
        commit(new Event.Cancelled(id, "cancelled by request"));
        settleBlocked();
    }

    /**
     * Runs every job that is due and ready, in (runAt, submission) order, until none is left.
     *
     * @return the number of attempts executed
     */
    public int tick() {
        int executed = 0;
        while (true) {
            Entry claimed;
            int attempt;
            synchronized (this) {
                claimed = nextReady();
                if (claimed == null) {
                    return executed;
                }
                attempt = claimed.attempts + 1;
                commit(new Event.Started(claimed.spec.id(), attempt));
            }
            executed++;
            Throwable failure = null;
            try {
                JobHandler h = handlers.get(claimed.spec.type());
                if (h == null) {
                    throw new IllegalStateException("no handler for type " + claimed.spec.type());
                }
                h.run(claimed.spec.payload());
            } catch (InterruptedException ex) {
                // Leave the attempt open (RUNNING): recovery will count it as a crashed attempt.
                Thread.currentThread().interrupt();
                return executed;
            } catch (Exception ex) {
                failure = ex;
            }
            synchronized (this) {
                if (failure == null) {
                    commit(new Event.Succeeded(claimed.spec.id()));
                } else {
                    finishFailed(claimed, describe(failure), failure instanceof PermanentFailure);
                }
                settleBlocked();
            }
        }
    }

    /** Earliest time at which some ready job becomes due, if any. */
    public synchronized Optional<Long> nextWakeup() {
        long best = Long.MAX_VALUE;
        boolean found = false;
        for (Entry e : jobs.values()) {
            if (e.state == State.PENDING && depsSucceeded(e) && e.runAt < best) {
                best = e.runAt;
                found = true;
            }
        }
        return found ? Optional.of(best) : Optional.empty();
    }

    public synchronized Optional<JobView> status(String id) {
        Entry e = jobs.get(id);
        return e == null ? Optional.empty() : Optional.of(view(e));
    }

    /** All jobs in submission order. */
    public synchronized List<JobView> all() {
        List<JobView> out = new ArrayList<>();
        for (Entry e : jobs.values()) {
            out.add(view(e));
        }
        return out;
    }

    /** True when every job is terminal. */
    public synchronized boolean isIdle() {
        return jobs.values().stream().allMatch(e -> e.state.isTerminal());
    }

    // ---- decisions -------------------------------------------------------------------------------

    private void finishFailed(Entry e, String error, boolean permanent) {
        if (permanent || e.attempts >= policy.maxAttempts()) {
            commit(new Event.Died(e.spec.id(), e.attempts, error));
        } else {
            commit(new Event.Failed(e.spec.id(), e.attempts, error, time.now() + policy.delayAfter(e.attempts)));
        }
    }

    private Entry nextReady() {
        long now = time.now();
        Entry best = null;
        for (Entry e : jobs.values()) {
            if (e.state != State.PENDING || e.runAt > now || !depsSucceeded(e)) {
                continue;
            }
            if (best == null || e.runAt < best.runAt || (e.runAt == best.runAt && e.seq < best.seq)) {
                best = e;
            }
        }
        return best;
    }

    private boolean depsSucceeded(Entry e) {
        for (String dep : e.spec.dependsOn()) {
            if (jobs.get(dep).state != State.SUCCEEDED) {
                return false;
            }
        }
        return true;
    }

    /** Cancels PENDING jobs with a DEAD or CANCELLED dependency, to a fixed point. Idempotent. */
    private void settleBlocked() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Entry e : new ArrayList<>(jobs.values())) {
                if (e.state != State.PENDING) {
                    continue;
                }
                for (String dep : e.spec.dependsOn()) {
                    State s = jobs.get(dep).state;
                    if (s == State.DEAD || s == State.CANCELLED) {
                        commit(new Event.Cancelled(e.spec.id(), "dependency " + dep + " is " + s));
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    // ---- journal + fold --------------------------------------------------------------------------

    private void commit(Event e) {
        journal.append(EventCodec.encode(e));
        apply(e);
    }

    private void apply(Event ev) {
        switch (ev) {
            case Event.Submitted s -> {
                if (jobs.containsKey(s.id())) {
                    throw new CorruptJournalException("duplicate submit of " + s.id());
                }
                for (String dep : s.dependsOn()) {
                    if (!jobs.containsKey(dep)) {
                        throw new CorruptJournalException("job " + s.id() + " depends on unknown " + dep);
                    }
                }
                jobs.put(s.id(), new Entry(s, jobs.size()));
            }
            case Event.Started s -> {
                Entry e = expect(s.id(), State.PENDING);
                if (s.attempt() != e.attempts + 1) {
                    throw new CorruptJournalException("out-of-order attempt for " + s.id());
                }
                e.attempts = s.attempt();
                e.state = State.RUNNING;
            }
            case Event.Succeeded s -> expect(s.id(), State.RUNNING).state = State.SUCCEEDED;
            case Event.Failed f -> {
                Entry e = expect(f.id(), State.RUNNING);
                if (f.attempt() != e.attempts) {
                    throw new CorruptJournalException("attempt mismatch for " + f.id());
                }
                e.state = State.PENDING;
                e.runAt = f.nextRunAt();
                e.lastError = f.error();
            }
            case Event.Died d -> {
                Entry e = expect(d.id(), State.RUNNING);
                e.state = State.DEAD;
                e.lastError = d.error();
            }
            case Event.Cancelled c -> {
                Entry e = expect(c.id(), State.PENDING);
                e.state = State.CANCELLED;
                e.lastError = c.reason();
            }
        }
    }

    private Entry expect(String id, State required) {
        Entry e = jobs.get(id);
        if (e == null) {
            throw new CorruptJournalException("event for unknown job " + id);
        }
        if (e.state != required) {
            throw new CorruptJournalException("job " + id + " is " + e.state + ", expected " + required);
        }
        return e;
    }

    private static JobView view(Entry e) {
        return new JobView(e.spec.id(), e.spec.type(), e.state, e.attempts, e.runAt, e.lastError);
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
