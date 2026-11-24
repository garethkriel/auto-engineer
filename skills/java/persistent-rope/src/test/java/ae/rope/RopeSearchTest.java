package ae.rope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RopeSearchTest {

    @Test
    void literalHits() {
        Rope r = Rope.of("the quick brown fox jumps over the lazy dog");
        assertEquals(0, r.indexOf("the"));
        assertEquals(31, r.indexOf("the", 1));
        assertEquals(16, r.indexOf("fox"));
        assertEquals(-1, r.indexOf("cat"));
        assertEquals(-1, r.indexOf("the", 32));
        assertEquals(42, r.indexOf("g"));
    }

    @Test
    void emptyNeedleAndClamping() {
        Rope r = Rope.of("abc");
        assertEquals(0, r.indexOf(""));
        assertEquals(2, r.indexOf("", 2));
        assertEquals(3, r.indexOf("", 99), "empty needle clamps to length like String.indexOf");
        assertEquals(0, r.indexOf("", -5));
        assertEquals(0, r.indexOf("a", -5));
        assertEquals(-1, r.indexOf("abcd"));
        assertEquals(-1, Rope.empty().indexOf("a"));
        assertEquals(0, Rope.empty().indexOf(""));
    }

    @Test
    void needleSpanningLeafBoundariesAtEveryShift() {
        String needle = "NEEDLE";
        for (int pos = 0; pos <= 70; pos++) {
            String text = "x".repeat(pos) + needle + "y".repeat(70 - pos);
            Rope r = Rope.of(text);
            assertEquals(pos, r.indexOf(needle), "needle at " + pos);
            assertEquals(-1, r.indexOf(needle, pos + 1), "after needle at " + pos);
            assertEquals(pos, r.indexOf(needle, pos), "from == pos at " + pos);
        }
    }

    @Test
    void failureFunctionCases() {
        // 8 a's (indices 0-7) then b at 8: "aaab" occupies 5..8, found only if the matcher keeps its partial match
        assertEquals(5, Rope.of("aaaaaaaab" + "z".repeat(40)).indexOf("aaab"));
        assertEquals(2, Rope.of("ababababab".repeat(10)).indexOf("ababab", 1));
        assertEquals(98, Rope.of("ab".repeat(50) + "c").indexOf("abc"), "match is the very end of the text");
        assertEquals(-1, Rope.of("ab".repeat(50)).indexOf("abc"));
    }

    @Test
    void searchWorksOnEditedRopes() {
        Rope r = Rope.of("a".repeat(100)).insert(50, "NEEDLE").delete(0, 10).concat(Rope.of("tail"));
        String s = r.toString();
        assertEquals(s.indexOf("NEEDLE"), r.indexOf("NEEDLE"));
        assertEquals(s.indexOf("atail"), r.indexOf("atail"));
        assertEquals(40, r.indexOf("NEEDLE"));
    }

    @Test
    void randomDifferentialAgainstString() {
        Random rnd = new Random(7);
        for (int iter = 0; iter < 3000; iter++) {
            int n = rnd.nextInt(200);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                sb.append((char) ('a' + rnd.nextInt(2)));
            }
            Rope r = Rope.of(sb);
            int m = 1 + rnd.nextInt(7);
            StringBuilder nb = new StringBuilder();
            for (int i = 0; i < m; i++) {
                nb.append((char) ('a' + rnd.nextInt(2)));
            }
            String needle = nb.toString();
            int from = rnd.nextInt(n + 5) - 2;
            assertEquals(sb.toString().indexOf(needle, from), r.indexOf(needle, from),
                    "text=" + sb + " needle=" + needle + " from=" + from);
        }
    }
}
