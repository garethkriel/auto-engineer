package leaderboard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LeaderboardTest {
    private Leaderboard sample() {
        Leaderboard lb = new Leaderboard();
        lb.record("bob", 10);
        lb.record("alice", 10);
        lb.record("Carl", 30);
        lb.record("dave", 5);
        lb.record("bob", 0);
        return lb;
    }

    @Test
    void unknownPlayerScoresZero() {
        assertEquals(0, new Leaderboard().score("nobody"));
    }

    @Test
    void recordAccumulatesIncludingNegatives() {
        Leaderboard lb = new Leaderboard();
        lb.record("a", 7);
        lb.record("a", -3);
        assertEquals(4, lb.score("a"));
    }

    @Test
    void rankingSortsByTotalDescThenNameAndSharesRanks() {
        List<Leaderboard.Entry> r = sample().ranking();
        assertEquals(List.of(
                new Leaderboard.Entry("Carl", 30, 1),
                new Leaderboard.Entry("alice", 10, 2),
                new Leaderboard.Entry("bob", 10, 2),
                new Leaderboard.Entry("dave", 5, 4)), r);
    }

    @Test
    void nameTieBreakIsCaseInsensitiveThenDeterministic() {
        Leaderboard lb = new Leaderboard();
        lb.record("b", 1);
        lb.record("B", 1);
        lb.record("A", 1);
        assertEquals(List.of("A", "B", "b"),
                lb.ranking().stream().map(Leaderboard.Entry::player).toList());
    }

    @Test
    void topLimitsAndClamps() {
        Leaderboard lb = sample();
        assertEquals(2, lb.top(2).size());
        assertEquals(4, lb.top(99).size());
        assertTrue(lb.top(0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> lb.top(-1));
    }

    @Test
    void byScoreIsOrderedHighestFirstWithSortedNames() {
        Map<Integer, List<String>> m = sample().byScore();
        assertEquals(List.of(30, 10, 5), List.copyOf(m.keySet()));
        assertEquals(List.of("alice", "bob"), m.get(10));
    }

    @Test
    void rejectsBadPlayerNames() {
        Leaderboard lb = new Leaderboard();
        assertThrows(IllegalArgumentException.class, () -> lb.record(null, 1));
        assertThrows(IllegalArgumentException.class, () -> lb.record("  ", 1));
    }

    @Test
    void overflowThrows() {
        Leaderboard lb = new Leaderboard();
        lb.record("a", Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> lb.record("a", 1));
        assertEquals(Integer.MAX_VALUE, lb.score("a"));
    }

    @Test
    void returnedCollectionsAreUnmodifiable() {
        Leaderboard lb = sample();
        assertThrows(UnsupportedOperationException.class, () -> lb.ranking().clear());
        assertThrows(UnsupportedOperationException.class, () -> lb.byScore().clear());
        assertThrows(UnsupportedOperationException.class, () -> lb.byScore().get(10).add("x"));
    }

    @Test
    void emptyBoardHasEmptyViews() {
        Leaderboard lb = new Leaderboard();
        assertTrue(lb.ranking().isEmpty());
        assertTrue(lb.byScore().isEmpty());
    }
}
