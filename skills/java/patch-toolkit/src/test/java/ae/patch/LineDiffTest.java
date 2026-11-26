package ae.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LineDiffTest {
    @Test
    void replaceDeletesBeforeInserts() {
        assertEquals(List.of(new Edit.Delete("x"), new Edit.Insert("y")),
            LineDiff.diff(List.of("x"), List.of("y")));
    }

    @Test
    void literalScript() {
        List<Edit> s = LineDiff.diff(Lines.of("a b c d"), Lines.of("a c d e"));
        assertEquals(List.of(new Edit.Keep("a"), new Edit.Delete("b"), new Edit.Keep("c"),
            new Edit.Keep("d"), new Edit.Insert("e")), s);
    }

    @Test
    void emptyCases() {
        assertEquals(List.of(), LineDiff.diff(List.of(), List.of()));
        assertEquals(2, LineDiff.diff(List.of(), Lines.of("p q")).size());
        assertEquals(2, LineDiff.diff(Lines.of("p q"), List.of()).size());
    }

    private static int lcs(List<String> a, List<String> b) {
        int[][] t = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                t[i][j] = a.get(i - 1).equals(b.get(j - 1)) ? t[i - 1][j - 1] + 1
                    : Math.max(t[i - 1][j], t[i][j - 1]);
            }
        }
        return t[a.size()][b.size()];
    }

    @Test
    void randomScriptsAreCorrectAndMinimal() {
        Random rnd = new Random(7);
        for (int round = 0; round < 400; round++) {
            List<String> a = new ArrayList<>();
            List<String> b = new ArrayList<>();
            for (int i = rnd.nextInt(12); i > 0; i--) {
                a.add("" + (char) ('a' + rnd.nextInt(4)));
            }
            for (int i = rnd.nextInt(12); i > 0; i--) {
                b.add("" + (char) ('a' + rnd.nextInt(4)));
            }
            List<Edit> s = LineDiff.diff(a, b);
            assertEquals(a, LineDiff.oldSide(s));
            assertEquals(b, LineDiff.newSide(s));
            long changes = s.stream().filter(e -> !(e instanceof Edit.Keep)).count();
            assertEquals(a.size() + b.size() - 2 * lcs(a, b), changes, a + " -> " + b);
        }
    }

    @Test
    void largeMostlyEqualInputIsFast() {
        // 40000 lines with edits in the middle: without prefix/suffix trimming the LCS table would be
        // about 30000 x 30000 ints and exhaust the heap
        List<String> a = new ArrayList<>();
        for (int i = 0; i < 40000; i++) {
            a.add("line" + i);
        }
        List<String> b = new ArrayList<>(a);
        b.set(20000, "changed");
        b.add(10000, "inserted");
        long t0 = System.nanoTime();
        List<Edit> s = LineDiff.diff(a, b);
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L);
        assertEquals(3, s.stream().filter(e -> !(e instanceof Edit.Keep)).count());
    }
}
