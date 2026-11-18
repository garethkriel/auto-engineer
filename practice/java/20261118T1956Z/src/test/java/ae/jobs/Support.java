package ae.jobs;

import ae.jobs.journal.Journal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared test fixtures: manual clock, crash-injecting journal, handler recorder. */
final class Support {
    private Support() {
    }

    static final class ManualClock implements TimeSource {
        private long now;

        ManualClock(long start) {
            this.now = start;
        }

        @Override
        public long now() {
            return now;
        }

        void set(long t) {
            now = t;
        }
    }

    static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SimulatedCrash() {
            super("simulated crash");
        }
    }

    /** Lets {@code allowed} appends through, then every later append "crashes" before touching the delegate. */
    static final class CrashingJournal implements Journal {
        private final Journal delegate;
        private int allowed;

        CrashingJournal(Journal delegate, int allowed) {
            this.delegate = delegate;
            this.allowed = allowed;
        }

        @Override
        public void append(String line) {
            if (allowed <= 0) {
                throw new SimulatedCrash();
            }
            allowed--;
            delegate.append(line);
        }

        @Override
        public List<String> readAll() {
            return delegate.readAll();
        }
    }

    /** Runs ticks, advancing the clock to the next wake-up, until nothing is left to do. */
    static void drain(JobScheduler s, ManualClock clock) {
        for (int guard = 0; guard < 10_000; guard++) {
            s.tick();
            var wake = s.nextWakeup();
            if (wake.isEmpty()) {
                return;
            }
            if (wake.get() <= clock.now()) {
                throw new AssertionError("ready job at " + wake.get() + " not run at " + clock.now());
            }
            clock.set(wake.get());
        }
        throw new AssertionError("drain did not terminate");
    }

    static Map<String, State> states(JobScheduler s) {
        Map<String, State> m = new LinkedHashMap<>();
        for (JobView v : s.all()) {
            m.put(v.id(), v.state());
        }
        return m;
    }

    static List<Event> decodeAll(Journal j) {
        List<Event> out = new ArrayList<>();
        for (String line : j.readAll()) {
            out.add(EventCodec.decode(line));
        }
        return out;
    }
}
