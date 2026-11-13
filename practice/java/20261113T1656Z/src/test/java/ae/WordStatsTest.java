package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;

class WordStatsTest {
    @Test
    void tokenizeLowercasesAndSplitsOnPunctuation() {
        assertEquals(List.of("hello", "world", "it's"), WordStats.tokenize("Hello, WORLD! It's 42"));
    }

    @Test
    void tokenizeEmptyAndSymbolsOnlyGiveEmptyList() {
        assertTrue(WordStats.tokenize("").isEmpty());
        assertTrue(WordStats.tokenize("123 -- ...").isEmpty());
    }

    @Test
    void tokenizeNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> WordStats.tokenize(null));
    }

    @Test
    void topWordsOrdersByCountThenAlphabetically() {
        List<WordCount> top = WordStats.topWords("b a b c a d b", 3);
        assertEquals(List.of(new WordCount("b", 3), new WordCount("a", 2), new WordCount("c", 1)), top);
    }

    @Test
    void topWordsTieBreaksAlphabetically() {
        assertEquals(List.of(new WordCount("apple", 1), new WordCount("banana", 1)),
                WordStats.topWords("Banana apple", 2));
    }

    @Test
    void topWordsReturnsAllWhenNExceedsDistinct() {
        assertEquals(2, WordStats.topWords("x y x", 10).size());
    }

    @Test
    void topWordsZeroIsEmptyAndNegativeThrows() {
        assertTrue(WordStats.topWords("x", 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> WordStats.topWords("x", -1));
    }

    @Test
    void topWordsResultIsImmutable() {
        List<WordCount> top = WordStats.topWords("x", 1);
        assertThrows(UnsupportedOperationException.class, () -> top.add(new WordCount("y", 1)));
    }

    @Test
    void groupByLengthIsSortedAndDistinct() {
        SortedMap<Integer, java.util.SortedSet<String>> g = WordStats.groupByLength("Go go dog cat at A");
        assertEquals(List.of(1, 2, 3), List.copyOf(g.keySet()));
        assertEquals(List.of("a"), List.copyOf(g.get(1)));
        assertEquals(List.of("at", "go"), List.copyOf(g.get(2)));
        assertEquals(List.of("cat", "dog"), List.copyOf(g.get(3)));
    }

    @Test
    void groupByLengthEmptyText() {
        assertTrue(WordStats.groupByLength("  ").isEmpty());
    }

    @Test
    void wordCountRejectsBadValues() {
        assertThrows(NullPointerException.class, () -> new WordCount(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new WordCount("a", 0));
    }
}
