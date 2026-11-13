package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvertedIndexTest {
    private InvertedIndex idx;

    @BeforeEach
    void setUp() {
        idx = new InvertedIndex();
        idx.add(1, "The quick brown fox");
        idx.add(2, "The lazy dog; the END.");
        idx.add(3, "Quick quick DOG");
    }

    @Test
    void searchIsCaseInsensitiveAndSorted() {
        assertEquals(List.of(1, 3), List.copyOf(idx.search("QUICK")));
    }

    @Test
    void searchUnknownIsEmpty() {
        assertTrue(idx.search("cat").isEmpty());
    }

    @Test
    void searchResultIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> idx.search("dog").add(9));
    }

    @Test
    void allIsIntersection() {
        assertEquals(Set.of(3), idx.all("quick", "dog"));
    }

    @Test
    void allWithUnknownTermIsEmpty() {
        assertTrue(idx.all("quick", "cat").isEmpty());
    }

    @Test
    void allWithNoTermsIsEmpty() {
        assertTrue(idx.all().isEmpty());
    }

    @Test
    void anyIsUnion() {
        assertEquals(List.of(1, 2, 3), List.copyOf(idx.any("fox", "lazy", "dog")));
    }

    @Test
    void addingSameDocAccumulates() {
        idx.add(1, "cat");
        assertEquals(Set.of(1), idx.search("cat"));
        assertEquals(Set.of(1, 3), idx.search("quick"));
    }

    @Test
    void topTermsOrderByCountThenAlpha() {
        // the=3, quick=3, dog=2, others 1
        assertEquals(List.of("quick", "the", "dog"), idx.topTerms(3));
    }

    @Test
    void topTermsLargerThanVocabularyReturnsAll() {
        assertEquals(List.of("quick", "the", "dog", "brown", "end", "fox", "lazy"), idx.topTerms(100));
    }

    @Test
    void topTermsZeroIsEmptyAndNegativeThrows() {
        assertTrue(idx.topTerms(0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> idx.topTerms(-1));
    }

    @Test
    void renderIsAlphabeticalLines() {
        InvertedIndex i = new InvertedIndex();
        i.add(2, "b a");
        i.add(1, "b");
        assertEquals("a -> [2]\nb -> [1, 2]", i.render());
    }

    @Test
    void renderEmptyIsEmptyString() {
        assertEquals("", new InvertedIndex().render());
    }

    @Test
    void tokenisationSplitsOnPunctuationAndKeepsDigits() {
        InvertedIndex i = new InvertedIndex();
        i.add(5, "foo-bar,baz42  ");
        assertEquals(Set.of(5), i.search("bar"));
        assertEquals(Set.of(5), i.search("baz42"));
        assertEquals("bar -> [5]\nbaz42 -> [5]\nfoo -> [5]", i.render());
    }

    @Test
    void invalidArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> idx.add(1, null));
        assertThrows(IllegalArgumentException.class, () -> idx.add(-1, "x"));
        assertThrows(NullPointerException.class, () -> idx.search(null));
    }
}
