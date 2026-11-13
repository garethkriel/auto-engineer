package practice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WordStatsTest {
    @Test
    void countLowercasesAndSplits() {
        Map<String, Integer> m = WordStats.count("The cat, the DOG; the cat!");
        assertEquals(Map.of("the", 3, "cat", 2, "dog", 1), m);
    }

    @Test
    void countKeepsApostrophes() {
        assertEquals(Map.of("don't", 1, "go", 1), WordStats.count("Don't go"));
    }

    @Test
    void countEmptyAndNull() {
        assertTrue(WordStats.count("  ,, ").isEmpty());
        assertThrows(NullPointerException.class, () -> WordStats.count(null));
    }

    @Test
    void topOrdersByCountThenAlpha() {
        Map<String, Integer> m = Map.of("b", 2, "a", 2, "c", 5, "d", 1);
        List<Map.Entry<String, Integer>> t = WordStats.top(m, 3);
        assertEquals(List.of("c", "a", "b"), t.stream().map(Map.Entry::getKey).toList());
        assertEquals(5, t.get(0).getValue());
    }

    @Test
    void topHandlesLargeAndInvalidN() {
        Map<String, Integer> m = Map.of("a", 1);
        assertEquals(1, WordStats.top(m, 10).size());
        assertEquals(0, WordStats.top(m, 0).size());
        assertThrows(IllegalArgumentException.class, () -> WordStats.top(m, -1));
    }

    @Test
    void groupByCountDescendingSorted() {
        Map<Integer, List<String>> g = WordStats.groupByCount(Map.of("z", 1, "a", 1, "m", 3));
        assertEquals(List.of(3, 1), List.copyOf(g.keySet()));
        assertEquals(List.of("a", "z"), g.get(1));
    }

    @Test
    void longestWordFirstWinsTies() {
        assertEquals("quick", WordStats.longestWord("the quick brown fox"));
        assertEquals("", WordStats.longestWord("... !!"));
    }
}
