package ae.broker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Seeded random simulation checked against an oracle of what was produced and committed. */
class BrokerSimulationTest {

    @Test
    void randomRunsKeepSafetyAndEventuallyDeliverEverything() {
        for (long seed = 1; seed <= 40; seed++) run(seed);
    }

    private void run(long seed) {
        Random rnd = new Random(seed);
        AtomicLong now = new AtomicLong(0);
        Broker b = new Broker(now::get, 50);
        b.createTopic("t", 5);
        String[] members = {"m0", "m1", "m2", "m3"};
        Map<String, Integer> gen = new HashMap<>();
        Set<String> live = new HashSet<>();
        Map<TopicPartition, Set<Long>> delivered = new HashMap<>();
        Map<TopicPartition, Long> produced = new HashMap<>();
        TopicPartition[] all = new TopicPartition[5];
        for (int i = 0; i < 5; i++) all[i] = new TopicPartition("t", i);

        for (int step = 0; step < 400; step++) {
            String m = members[rnd.nextInt(members.length)];
            switch (rnd.nextInt(6)) {
                case 0 -> {
                    Broker.Metadata md = b.produce("t", rnd.nextBoolean() ? "k" + rnd.nextInt(7) : null, "s" + step);
                    produced.merge(new TopicPartition("t", md.partition()), 1L, Long::sum);
                }
                case 1 -> {
                    Broker.Membership ms = b.join("g", m, Set.of("t"));
                    live.add(m);
                    gen.put(m, ms.generation());
                }
                case 2 -> {
                    try { b.leave("g", m); } catch (UnknownMemberException ignored) { /* not a member */ }
                    live.remove(m);
                }
                case 3 -> now.addAndGet(rnd.nextInt(40));
                case 4 -> {
                    try {
                        gen.put(m, b.heartbeat("g", m).generation());
                        for (Broker.Message x : b.poll("g", m, gen.get(m), 1 + rnd.nextInt(5))) {
                            delivered.computeIfAbsent(new TopicPartition(x.topic(), x.partition()),
                                    k -> new HashSet<>()).add(x.offset());
                        }
                    } catch (UnknownMemberException | StaleGenerationException ignored) { /* evicted */ }
                }
                default -> {
                    try {
                        Broker.Membership ms = b.heartbeat("g", m);
                        gen.put(m, ms.generation());
                        if (ms.assignment().isEmpty()) break;
                        TopicPartition p = ms.assignment().get(rnd.nextInt(ms.assignment().size()));
                        long have = delivered.getOrDefault(p, Set.of()).size();
                        long upTo = have == 0 ? 0 : rnd.nextInt((int) have + 1);
                        try {
                            b.commit("g", m, ms.generation(), p, upTo);
                        } catch (IllegalArgumentException ignored) { /* beyond this member's position */ }
                    } catch (UnknownMemberException ignored) { /* evicted */ }
                }
            }
            checkSingleOwner(b, members, live, now);
            for (TopicPartition p : all) {
                long c = b.committed("g", p);
                if (c >= 0) assertTrue(c <= b.logEnd("t", p.partition()), "seed " + seed + " commit past end");
            }
        }

        // Drain: one fresh member consumes everything uncommitted and commits it.
        now.addAndGet(10_000);
        Broker.Membership ms = b.join("g", "drain", Set.of("t"));
        assertTrue(ms.generation() > gen.values().stream().max(Integer::compare).orElse(0),
                "seed " + seed + ": drain join must start a newer generation");
        assertEquals(5, ms.assignment().size(), "seed " + seed + ": everyone else expired");
        Map<TopicPartition, Long> next = new HashMap<>();
        for (Broker.Message x : b.poll("g", "drain", ms.generation(), 100_000)) {
            TopicPartition p = new TopicPartition(x.topic(), x.partition());
            delivered.computeIfAbsent(p, k -> new HashSet<>()).add(x.offset());
            next.merge(p, 1L, Long::sum);
        }
        for (TopicPartition p : all) {
            long end = b.logEnd("t", p.partition());
            assertEquals(end, produced.getOrDefault(p, 0L), "seed " + seed + " produced count " + p);
            Set<Long> got = delivered.getOrDefault(p, Set.of());
            for (long off = 0; off < end; off++) {
                assertTrue(got.contains(off), "seed " + seed + " lost " + p + "@" + off);
            }
            b.commit("g", "drain", ms.generation(), p, end);
        }
        assertEquals(0, b.lag("g"));
    }

    /** Read-only view of the assignment: it must never refresh sessions, so expiry still happens. */
    private void checkSingleOwner(Broker b, String[] members, Set<String> live, AtomicLong now) {
        Map<TopicPartition, Integer> owners = new HashMap<>();
        for (List<TopicPartition> ps : b.assignments("g").values()) {
            for (TopicPartition p : ps) owners.merge(p, 1, Integer::sum);
        }
        for (Map.Entry<TopicPartition, Integer> e : owners.entrySet()) {
            assertEquals(1, e.getValue(), "partition " + e.getKey() + " has several owners");
        }
    }
}
