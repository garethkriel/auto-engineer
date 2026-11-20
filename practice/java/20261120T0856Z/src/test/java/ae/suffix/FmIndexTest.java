package ae.suffix;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FmIndexTest {
    @Test
    void bananaAllSampleSteps() {
        for (int step = 1; step <= 8; step++) {
            FmIndex fm = new FmIndex("banana", step);
            assertEquals(2, fm.count("ana"), "step " + step);
            assertEquals(List.of(1, 3), fm.locate("ana"), "step " + step);
            assertEquals(List.of(0), fm.locate("banana"), "step " + step);
            assertEquals(List.of(1, 3, 5), fm.locate("a"), "step " + step);
            assertEquals(0, fm.count("nab"));
            assertEquals(List.of(), fm.locate("z"));
        }
    }

    @Test
    void overlappingMatches() {
        assertEquals(List.of(0, 1, 2), new FmIndex("aaaa", 3).locate("aa"));
        assertEquals(1, new FmIndex("aaaa", 3).count("aaaa"));
        assertEquals(0, new FmIndex("aaaa", 3).count("aaaaa"));
    }

    @Test
    void argumentValidation() {
        assertThrows(IllegalArgumentException.class, () -> new FmIndex("abc", 0));
        assertThrows(IllegalArgumentException.class, () -> new FmIndex("a\0c", 2));
        assertThrows(IllegalArgumentException.class, () -> new FmIndex("abc", 2).count(""));
        assertThrows(NullPointerException.class, () -> new FmIndex(null, 2));
    }

    @Test
    void sentinelPatternMatchesNothing() {
        FmIndex fm = new FmIndex("banana", 2);
        assertEquals(0, fm.count("a\0"));
        assertEquals(0, fm.count("\0"));
        assertEquals(List.of(), fm.locate("\0b"));
    }

    @Test
    void emptyText() {
        FmIndex fm = new FmIndex("", 4);
        assertEquals(0, fm.count("a"));
        assertEquals(List.of(), fm.locate("a"));
    }

    @Test
    void randomDifferentialAgainstIndexOf() {
        Random r = new Random(555);
        for (int iter = 0; iter < 300; iter++) {
            String s = Naive.random(r, r.nextInt(120), 1 + r.nextInt(4)); // crosses 16-row checkpoints
            int step = 1 + r.nextInt(9);
            FmIndex fm = new FmIndex(s, step);
            for (int q = 0; q < 10; q++) {
                String p = Naive.random(r, 1 + r.nextInt(5), 1 + r.nextInt(4));
                List<Integer> expected = Naive.occurrences(s, p);
                assertEquals(expected.size(), fm.count(p), s + " / " + p + " step " + step);
                assertEquals(expected, fm.locate(p), s + " / " + p + " step " + step);
            }
        }
    }

    @Test
    void wideAlphabetIncludingNonAscii() {
        String s = "héllo wörld, héllo ✓ wörld";
        FmIndex fm = new FmIndex(s, 3);
        assertEquals(Naive.occurrences(s, "héllo"), fm.locate("héllo"));
        assertEquals(Naive.occurrences(s, "ö"), fm.locate("ö"));
        assertEquals(1, fm.count("✓"));
    }

    @Test
    void largeTextAgreesWithSuffixIndex() {
        Random r = new Random(11);
        String s = Naive.random(r, 300_000, 4);
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            FmIndex fm = new FmIndex(s, 32);
            SuffixIndex idx = new SuffixIndex(s);
            for (int q = 0; q < 100; q++) {
                String p = Naive.random(r, 3 + r.nextInt(8), 4);
                assertEquals(idx.count(p), fm.count(p), p);
                if (idx.count(p) <= 50) assertEquals(idx.occurrences(p), fm.locate(p), p);
            }
        });
    }
}
