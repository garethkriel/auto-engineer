package ae.raft;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimClusterTest {

    static SimCluster reliable(int n, long seed) {
        return new SimCluster(n, seed, 0.0, 0.0, 1, 3);
    }

    static void run(SimCluster c, int ticks) {
        for (int i = 0; i < ticks; i++) c.tick();
    }

    static int leaderOf(SimCluster c) {
        Optional<Integer> l = c.leader();
        assertTrue(l.isPresent(), "expected a leader at t=" + c.time());
        return l.get();
    }

    @Test
    void threeNodesElectOneLeaderAndFollowersAgreeOnTerm() {
        SimCluster c = reliable(3, 7);
        run(c, 200);
        int l = leaderOf(c);
        long leaders = 0;
        for (int i = 0; i < 3; i++) {
            if (c.node(i).role() == RaftNode.Role.LEADER) leaders++;
            assertEquals(c.node(l).term(), c.node(i).term());
        }
        assertEquals(1, leaders);
        assertEquals(List.of(), c.violations());
    }

    @Test
    void fiveNodesElectAndReplicateProposals() {
        SimCluster c = reliable(5, 11);
        run(c, 200);
        assertTrue(c.propose("a").isPresent());
        assertTrue(c.propose("b").isPresent());
        assertTrue(c.propose("c").isPresent());
        run(c, 100);
        for (int i = 0; i < 5; i++) {
            assertEquals(List.of("a", "b", "c"), c.node(i).committed(), "node " + i);
        }
        assertEquals(List.of("a", "b", "c"), c.globalCommitted());
    }

    @Test
    void proposeWithoutLeaderIsEmpty() {
        SimCluster c = reliable(3, 1);
        assertEquals(Optional.empty(), c.propose("x"));
    }

    @Test
    void crashedLeaderIsReplacedInHigherTermAndOldLeaderRejoinsAsFollower() {
        SimCluster c = reliable(3, 3);
        run(c, 200);
        int old = leaderOf(c);
        int oldTerm = c.node(old).term();
        c.propose("one");
        run(c, 50);
        c.crash(old);
        run(c, 300);
        int next = leaderOf(c);
        assertNotEquals(old, next);
        assertTrue(c.node(next).term() > oldTerm);
        c.propose("two");
        run(c, 50);
        c.restart(old);
        run(c, 200);
        assertEquals(RaftNode.Role.FOLLOWER, c.node(old).role());
        for (int i = 0; i < 3; i++) assertEquals(List.of("one", "two"), c.node(i).committed());
        assertEquals(List.of(), c.violations());
    }

    @Test
    void minorityLeaderCannotCommitAndItsEntriesAreOverwrittenAfterHeal() {
        SimCluster c = reliable(5, 21);
        run(c, 200);
        int old = leaderOf(c);
        c.propose("base");
        run(c, 50);
        // isolate the leader plus one follower (minority of 2) from the other 3
        int buddy = (old + 1) % 5;
        Set<Integer> minority = Set.of(old, buddy);
        for (int a = 0; a < 5; a++) {
            for (int b = 0; b < 5; b++) {
                if (minority.contains(a) != minority.contains(b)) c.block(a, b);
            }
        }
        int lostIdx = c.node(old).propose("lost");
        assertTrue(lostIdx > 0);
        run(c, 400);
        assertEquals(1, c.node(old).committed().size(), "minority must not commit 'lost'");
        assertEquals("base", c.node(old).committed().get(0));
        // majority side elects a new leader and commits
        int majorityLeader = -1;
        for (int i = 0; i < 5; i++) {
            if (!minority.contains(i) && c.node(i).role() == RaftNode.Role.LEADER) majorityLeader = i;
        }
        assertTrue(majorityLeader >= 0, "majority side must have a leader");
        assertTrue(c.node(majorityLeader).propose("win") > 0);
        run(c, 100);
        c.healAll();
        run(c, 300);
        for (int i = 0; i < 5; i++) {
            assertEquals(List.of("base", "win"), c.node(i).committed(), "node " + i);
            assertFalse(c.node(i).log().stream().anyMatch(en -> en.command().equals("lost")), "node " + i);
        }
        assertEquals(List.of(), c.violations());
    }

    @Test
    void lossyDuplicatingNetworkStillConverges() {
        SimCluster c = new SimCluster(5, 99, 0.25, 0.15, 1, 8);
        run(c, 600);
        List<String> proposed = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String cmd = "cmd" + i;
            for (int tries = 0; tries < 100 && c.propose(cmd).isEmpty(); tries++) run(c, 5);
            proposed.add(cmd);
            run(c, 20);
        }
        run(c, 1500);
        List<String> global = c.globalCommitted();
        // proposals sent to a leader that was about to lose power may vanish, but order is kept
        assertTrue(global.size() >= 1);
        List<String> filtered = proposed.stream().filter(global::contains).toList();
        assertEquals(filtered, global.stream().filter(proposed::contains).toList());
        for (int i = 0; i < 5; i++) assertEquals(global, c.node(i).committed());
        assertEquals(List.of(), c.violations());
    }

    @Test
    void sameSeedGivesIdenticalTraceDifferentSeedDiffers() {
        String t1 = trace(5, 1234);
        String t2 = trace(5, 1234);
        String t3 = trace(5, 4321);
        assertEquals(t1, t2);
        assertNotEquals(t1, t3);
    }

    static String trace(int n, long seed) {
        SimCluster c = new SimCluster(n, seed, 0.1, 0.05, 1, 5);
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < 400; t++) {
            c.tick();
            if (t % 10 == 0) c.propose("p" + t);
            for (int i = 0; i < n; i++) {
                sb.append(c.node(i).role().name().charAt(0)).append(c.node(i).term()).append(':')
                        .append(c.node(i).log().size()).append(' ');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void crashedAndPartitionedNodesReceiveNothing() {
        SimCluster c = reliable(3, 5);
        run(c, 200);
        int l = leaderOf(c);
        int f = (l + 1) % 3;
        c.crash(f);
        int before = c.node(f).log().size();
        c.propose("x");
        run(c, 100);
        assertEquals(before, c.node(f).log().size());
        assertTrue(c.isDown(f));
        c.restart(f);
        assertFalse(c.isDown(f));
        run(c, 100);
        assertEquals(List.of("x"), c.node(f).committed());
    }

    /** Randomised chaos: crashes, partitions, heals and proposals; safety must hold on every seed. */
    @Test
    void chaosAcrossManySeedsKeepsSafetyAndConvergesAfterHealing() {
        for (long seed = 1; seed <= 60; seed++) {
            int n = (seed % 2 == 0) ? 5 : 3;
            SimCluster c = new SimCluster(n, seed, 0.1, 0.05, 1, 6);
            Random chaos = new Random(seed * 7919);
            int counter = 0;
            for (int t = 0; t < 1500; t++) {
                int roll = chaos.nextInt(100);
                if (roll < 3) {
                    c.crash(chaos.nextInt(n));
                } else if (roll < 6) {
                    c.restart(chaos.nextInt(n));
                } else if (roll < 8) {
                    c.block(chaos.nextInt(n), chaos.nextInt(n));
                } else if (roll < 10) {
                    c.healAll();
                } else if (roll < 30) {
                    c.propose("s" + seed + "-" + counter++);
                }
                c.tick();
                assertEquals(List.of(), c.violations(), "seed " + seed + " t=" + t);
            }
            // heal everything, restart everyone and let the cluster settle
            c.healAll();
            for (int i = 0; i < n; i++) c.restart(i);
            run(c, 1200);
            assertTrue(c.leader().isPresent(), "seed " + seed + ": leader after heal");
            Optional<Integer> idx = c.propose("final-" + seed);
            assertTrue(idx.isPresent(), "seed " + seed);
            run(c, 400);
            List<String> global = c.globalCommitted();
            assertEquals("final-" + seed, global.get(global.size() - 1), "seed " + seed);
            for (int i = 0; i < n; i++) {
                assertEquals(global, c.node(i).committed(), "seed " + seed + " node " + i);
                assertEquals(c.node(0).log(), c.node(i).log(), "seed " + seed + " node " + i);
            }
            assertEquals(global.size(), new HashSet<>(global).size(), "no command committed twice, seed " + seed);
        }
    }

    @Test
    void logMatchingHoldsAtEveryStepOfAChaoticRun() {
        SimCluster c = new SimCluster(5, 77, 0.15, 0.1, 1, 6);
        Random chaos = new Random(5);
        for (int t = 0; t < 1500; t++) {
            if (chaos.nextInt(40) == 0) c.crash(chaos.nextInt(5));
            if (chaos.nextInt(40) == 0) c.restart(chaos.nextInt(5));
            if (chaos.nextInt(60) == 0) c.block(chaos.nextInt(5), chaos.nextInt(5));
            if (chaos.nextInt(80) == 0) c.healAll();
            if (chaos.nextInt(5) == 0) c.propose("m" + t);
            c.tick();
            for (int a = 0; a < 5; a++) {
                for (int b = a + 1; b < 5; b++) {
                    assertLogMatching(c.node(a).log(), c.node(b).log());
                }
            }
        }
    }

    /** If two logs hold an entry with the same index and term, they are identical up to that index. */
    static void assertLogMatching(List<LogEntry> x, List<LogEntry> y) {
        int m = Math.min(x.size(), y.size());
        int lastSame = 0;
        for (int i = 0; i < m; i++) {
            if (x.get(i).term() == y.get(i).term()) lastSame = i + 1;
        }
        for (int i = 0; i < lastSame; i++) {
            assertEquals(x.get(i), y.get(i), "log matching violated at index " + (i + 1));
        }
    }
}
