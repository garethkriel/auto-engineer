package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WordToolsTest {
    @Test
    void topWordsExactOrder() {
        // the:3, a:2, cat:2 -> the, a, cat (tie broken alphabetically)
        assertEquals(List.of("the", "a", "cat"), WordTools.topWords("The cat; a cat. THE the a", 3));
    }

    @Test
    void topWordsLimitsAndHandlesFewerWords() {
        assertEquals(List.of("x"), WordTools.topWords("x x x", 5));
        assertEquals(List.of(), WordTools.topWords("x y", 0));
        assertEquals(List.of(), WordTools.topWords("123 !!!", 3));
    }

    @Test
    void topWordsRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> WordTools.topWords("a", -1));
        assertThrows(NullPointerException.class, () -> WordTools.topWords(null, 1));
    }

    @Test
    void anagramGroupsFindsSortedGroups() {
        List<List<String>> g = WordTools.anagramGroups(List.of("Listen", "silent", "enlist", "google", "tinsel", "cat", "act", "dog", "Act"));
        assertEquals(List.of(List.of("act", "cat"), List.of("enlist", "listen", "silent", "tinsel")), g);
    }

    @Test
    void anagramGroupsIgnoresSingletonsAndDuplicates() {
        assertEquals(List.of(), WordTools.anagramGroups(List.of("a", "A", "b")));
        assertEquals(List.of(), WordTools.anagramGroups(List.of()));
    }

    @Test
    void wrapGreedy() {
        assertEquals("the quick\nbrown fox\njumps", WordTools.wrap("the quick brown fox jumps", 9));
    }

    @Test
    void wrapKeepsLongWordAndCollapsesWhitespace() {
        assertEquals("a\nsupercalifragilistic\nb", WordTools.wrap("  a   supercalifragilistic \n b ", 5));
        assertEquals("", WordTools.wrap("   ", 5));
    }

    @Test
    void wrapExactWidthFitsOnOneLine() {
        assertEquals("abc de", WordTools.wrap("abc de", 6));
        assertEquals("abc\nde", WordTools.wrap("abc de", 5));
    }

    @Test
    void wrapRejectsBadWidth() {
        assertThrows(IllegalArgumentException.class, () -> WordTools.wrap("a", 0));
    }

    @Test
    void commonWordsIsSortedIntersection() {
        assertEquals(Set.of("b", "c"), WordTools.commonWords("A b, C c", "c B d"));
        assertEquals(List.of("b", "c"), List.copyOf(WordTools.commonWords("A b, C c", "c B d")));
        assertTrue(WordTools.commonWords("x", "y").isEmpty());
    }
}
