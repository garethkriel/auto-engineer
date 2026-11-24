package ae.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TextJustifierTest {
    private static final int[] NONE = new int[0];

    private static List<String> plain(String text, int width) throws LayoutException {
        return TextJustifier.justify(text, width, w -> NONE);
    }

    @Test
    void exactFitLineNeedsNoStretch() throws LayoutException {
        // "aaa bbb ccc" is exactly 11 wide: demerits 100 + 100 beats every alternative.
        assertEquals(List.of("aaa bbb ccc", "ddd"), plain("aaa bbb ccc ddd", 11));
    }

    @Test
    void extraSpaceIsSpreadLeftFirst() throws LayoutException {
        // Width 12: three words are 11 wide, d=1 over two glues -> first glue gets the extra space.
        assertEquals(List.of("aaa  bbb ccc", "ddd"), plain("aaa bbb ccc ddd", 12));
    }

    @Test
    void relaxesToleranceWhenNothingFitsTheDefault() throws LayoutException {
        // Width 9: only 2+2 is possible; d=2 on one glue -> badness 800, above tolerance 100 and 400.
        assertEquals(List.of("aaa   bbb", "ccc ddd"), plain("aaa bbb ccc ddd", 9));
    }

    @Test
    void hyphenationMakesOtherwiseImpossibleLayoutsFeasible() throws LayoutException {
        assertEquals(List.of("abc-", "def"), TextJustifier.justify("abcdef", 4, w -> new int[] {3}));
        assertEquals(List.of("aaa bbb-", "bbb"),
                TextJustifier.justify("aaa bbbbbb", 8, w -> w.equals("bbbbbb") ? new int[] {3} : NONE));
        LayoutException ex = assertThrows(LayoutException.class, () -> plain("abcdef", 4));
        assertTrue(ex.getMessage().contains("no feasible layout"), ex.getMessage());
    }

    @Test
    void whitespaceIsNormalised() throws LayoutException {
        assertEquals(List.of(), plain("", 10));
        assertEquals(List.of(), plain(" \n\t ", 10));
        assertEquals(List.of("aaa bbb"), plain("  aaa \n\n  bbb\t", 10));
    }

    @Test
    void badArgumentsAreRejected() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> plain("a", 0)).getMessage().contains("width"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TextJustifier.justify("abc", 10, w -> new int[] {0})).getMessage().contains("hyphen"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TextJustifier.justify("abc", 10, w -> new int[] {3})).getMessage().contains("hyphen"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TextJustifier.justify("abcdef", 10, w -> new int[] {4, 2})).getMessage().contains("hyphen"));
    }

    @Test
    void randomTextsRenderCorrectly() throws LayoutException {
        Random rnd = new Random(99);
        String[] vocab = {"a", "to", "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
            "extraordinarily", "typesetting", "paragraph", "optimal", "it"};
        int solved = 0;
        int hyphens = 0;
        for (int iter = 0; iter < 300; iter++) {
            int n = 1 + rnd.nextInt(60);
            List<String> words = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                words.add(vocab[rnd.nextInt(vocab.length)]);
            }
            String text = String.join(" ", words);
            int width = 12 + rnd.nextInt(30);
            List<String> lines;
            try {
                lines = TextJustifier.justify(text, width, w -> w.length() >= 8
                        ? new int[] {3, w.length() - 4} : NONE);
            } catch (LayoutException e) {
                continue;
            }
            solved++;
            StringBuilder rebuilt = new StringBuilder();
            for (int k = 0; k < lines.size(); k++) {
                String line = lines.get(k);
                boolean last = k == lines.size() - 1;
                assertTrue(line.length() <= width, "too long: '" + line + "' width " + width);
                if (!last) {
                    assertEquals(width, line.length(), "line " + k + " '" + line + "' of " + lines);
                } else {
                    assertTrue(!line.contains("  "), "last line must be single-spaced: " + line);
                }
                assertTrue(!line.startsWith(" ") && !line.endsWith(" "), "edge space: '" + line + "'");
                if (!last && line.endsWith("-")) {
                    hyphens++;
                    rebuilt.append(line.substring(0, line.length() - 1).replaceAll(" +", " "));
                } else {
                    rebuilt.append(line.replaceAll(" +", " "));
                    if (!last) {
                        rebuilt.append(' ');
                    }
                }
            }
            assertEquals(text, rebuilt.toString(), "width " + width + " lines " + lines);
        }
        assertTrue(solved > 250, "solved " + solved);
        assertTrue(hyphens > 20, "hyphens " + hyphens);
    }
}
