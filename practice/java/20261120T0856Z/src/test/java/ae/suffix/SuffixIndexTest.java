package ae.suffix;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SuffixIndexTest {
    @Test
    void bananaOccurrences() {
        SuffixIndex idx = new SuffixIndex("banana");
        assertEquals(List.of(1, 3), idx.occurrences("ana"));
        assertEquals(List.of(2), idx.occurrences("nan"));
        assertEquals(3, idx.count("a"));
        assertEquals(1, idx.count("banana"));
        assertEquals(0, idx.count("bananas"));
        assertEquals(0, idx.count("x"));
        assertTrue(idx.contains("nana"));
        assertFalse(idx.contains("nab"));
    }

    @Test
    void emptyPatternRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SuffixIndex("abc").count(""));
        assertThrows(NullPointerException.class, () -> new SuffixIndex(null));
    }

    @Test
    void emptyTextHasNoMatches() {
        SuffixIndex idx = new SuffixIndex("");
        assertEquals(0, idx.count("a"));
        assertEquals("", idx.longestRepeatedSubstring());
        assertEquals(0L, idx.distinctSubstrings());
    }

    @Test
    void longestRepeatedKnownAnswers() {
        assertEquals("ana", new SuffixIndex("banana").longestRepeatedSubstring());
        assertEquals("", new SuffixIndex("abcd").longestRepeatedSubstring());
        assertEquals("aaa", new SuffixIndex("aaaa").longestRepeatedSubstring()); // overlap allowed
        // "ab" (at 0,6) and "cd" (at 3,9) both repeat with length 2; the smaller one wins
        assertEquals("ab", new SuffixIndex("abxcdyabzcd").longestRepeatedSubstring());
    }

    @Test
    void distinctAndKth() {
        SuffixIndex idx = new SuffixIndex("banana");
        assertEquals(15L, idx.distinctSubstrings());
        assertEquals(4L, new SuffixIndex("aaaa").distinctSubstrings());
        assertEquals("a", idx.kthSubstring(1));
        assertEquals("anan", idx.kthSubstring(4));
        assertEquals("b", idx.kthSubstring(6));
        assertEquals("nana", idx.kthSubstring(15));
        assertThrows(IllegalArgumentException.class, () -> idx.kthSubstring(0));
        assertThrows(IllegalArgumentException.class, () -> idx.kthSubstring(16));
    }

    @Test
    void longestCommonSubstringKnownAnswers() {
        assertEquals("abcd", SuffixIndex.longestCommonSubstring("xabcdy", "zabcdw"));
        assertEquals("", SuffixIndex.longestCommonSubstring("abc", "xyz"));
        assertEquals("", SuffixIndex.longestCommonSubstring("", "xyz"));
        assertEquals("ab", SuffixIndex.longestCommonSubstring("abxcd", "cdab")); // tie ab/cd
        assertEquals("a", SuffixIndex.longestCommonSubstring("xa", "ay")); // must not span a|b seam
        assertEquals("ab", SuffixIndex.longestCommonSubstring("ab", "ab"));
    }

    @Test
    void randomDifferentialAgainstNaive() {
        Random r = new Random(2026);
        for (int iter = 0; iter < 300; iter++) {
            String s = Naive.random(r, r.nextInt(41), 1 + r.nextInt(3));
            SuffixIndex idx = new SuffixIndex(s);
            for (int q = 0; q < 8; q++) {
                String p = Naive.random(r, 1 + r.nextInt(4), 1 + r.nextInt(3));
                assertEquals(Naive.occurrences(s, p), idx.occurrences(p), s + " / " + p);
                assertEquals(Naive.occurrences(s, p).size(), idx.count(p));
            }
            assertEquals(Naive.longestRepeated(s), idx.longestRepeatedSubstring(), s);
            List<String> all = new ArrayList<>(Naive.distinct(s));
            assertEquals(all.size(), idx.distinctSubstrings(), s);
            for (int k = 1; k <= all.size(); k++) assertEquals(all.get(k - 1), idx.kthSubstring(k), s);
            String other = Naive.random(r, r.nextInt(30), 1 + r.nextInt(3));
            assertEquals(
                    Naive.longestCommon(s, other),
                    SuffixIndex.longestCommonSubstring(s, other),
                    s + " | " + other);
        }
    }
}
