package ae.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.jobs.Support.ManualClock;
import ae.jobs.journal.InMemoryJournal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Random DAGs with scripted failures, checked against an oracle computed straight from the definitions. */
class OracleTest {
    private static final int MAX_ATTEMPTS = 3;

    @Test
    void randomWorkloadsMatchTheOracleAndRespectOrderingRules() {
        RetryPolicy policy = new RetryPolicy(MAX_ATTEMPTS, 7, 50);
        for (long seed = 1; seed <= 300; seed++) {
            Random rnd = new Random(seed);
            int n = 1 + rnd.nextInt(12);
            String[] ids = new String[n];
            List<Set<Integer>> deps = new ArrayList<>();
            int[] failures = new int[n];
            boolean[] permanent = new boolean[n];
            long[] runAt = new long[n];
            for (int i = 0; i < n; i++) {
                ids[i] = "j" + i;
                Set<Integer> d = new HashSet<>();
                for (int k = 0; k < i; k++) {
                    if (rnd.nextInt(4) == 0) {
                        d.add(k);
                    }
                }
                deps.add(d);
                failures[i] = rnd.nextInt(5); // 0..4 transient failures before it would succeed
                permanent[i] = rnd.nextInt(10) == 0;
                runAt[i] = rnd.nextInt(200);
            }

            ManualClock clock = new ManualClock(0);
            Set<String> succeededSoFar = new HashSet<>();
            Map<String, List<Long>> attemptTimes = new HashMap<>();
            Map<String, Integer> failuresLeft = new HashMap<>();
            Map<String, Set<String>> depNames = new HashMap<>();
            for (int i = 0; i < n; i++) {
                failuresLeft.put(ids[i], failures[i]);
                Set<String> names = new HashSet<>();
                deps.get(i).forEach(k -> names.add("j" + k));
                depNames.put(ids[i], names);
            }
            String at = "seed " + seed;
            JobHandler handler = payload -> {
                assertTrue(succeededSoFar.containsAll(depNames.get(payload)), at + ": " + payload + " ran early");
                attemptTimes.computeIfAbsent(payload, k -> new ArrayList<>()).add(clock.now());
                int idx = Integer.parseInt(payload.substring(1));
                if (permanent[idx]) {
                    throw new PermanentFailure("perm");
                }
                int left = failuresLeft.get(payload);
                if (left > 0) {
                    failuresLeft.put(payload, left - 1);
                    throw new IllegalStateException("transient");
                }
                succeededSoFar.add(payload);
            };
            JobScheduler s = new JobScheduler(new InMemoryJournal(), clock, policy, Map.of("w", handler));
            for (int i = 0; i < n; i++) {
                String[] depArray = deps.get(i).stream().map(k -> "j" + k).toArray(String[]::new);
                s.submit(JobSpec.of(ids[i], "w", ids[i]).after(depArray).at(runAt[i]));
            }
            Support.drain(s, clock);

            // oracle
            State[] expected = new State[n];
            for (int i = 0; i < n; i++) {
                boolean depsOk = deps.get(i).stream().allMatch(k -> expected[k] == State.SUCCEEDED);
                if (!depsOk) {
                    expected[i] = State.CANCELLED;
                } else if (!permanent[i] && failures[i] < MAX_ATTEMPTS) {
                    expected[i] = State.SUCCEEDED;
                } else {
                    expected[i] = State.DEAD;
                }
            }
            for (int i = 0; i < n; i++) {
                JobView v = s.status(ids[i]).orElseThrow();
                assertEquals(expected[i], v.state(), at + " " + ids[i]);
                int expectedAttempts = switch (expected[i]) {
                    case SUCCEEDED -> failures[i] + 1;
                    case DEAD -> permanent[i] ? 1 : MAX_ATTEMPTS;
                    default -> 0;
                };
                assertEquals(expectedAttempts, v.attempts(), at + " attempts of " + ids[i]);
                List<Long> times = attemptTimes.getOrDefault(ids[i], List.of());
                assertEquals(expectedAttempts, times.size(), at + " executions of " + ids[i]);
                if (!times.isEmpty()) {
                    assertTrue(times.get(0) >= runAt[i], at + " " + ids[i] + " started before its runAt");
                }
                for (int k = 1; k < times.size(); k++) {
                    assertTrue(times.get(k) >= times.get(k - 1) + policy.delayAfter(k),
                            at + " " + ids[i] + " retried too early: " + times);
                }
            }
            assertTrue(s.isIdle(), at);
        }
    }
}
