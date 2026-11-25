package ae.shellx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Compares WordExpander with real bash run with pathname expansion disabled (set -f). */
class BashDifferentialTest {
    private static final Map<String, String> ENV = Map.of(
        "A", "x y", "B", "b", "E", "", "S", " p  q ", "N", "7");

    /** Syntactically self-contained fragments; the generator concatenates them. */
    private static final String[] FRAGMENTS = {
        "a", "bc", "$A", "${A}", "$B", "${B}", "${E:-d}", "${E-d}", "${B:+p}", "${U-q r}", "${U:-${B}}",
        "${#A}", "${N:+\"s t\"}", "\"\"", "''", "'$A'", "\"$A\"", "\"a b\"", "\"\\$B\"", "\"\\z\"",
        "\\ ", "\\$A", "{x,y}", "{1..3}", "{3..1}", "{01..03}", "{1..10..4}", "{a,{b,c}}", "{a}", "{",
        "}", ",", "{,z}", "{$B,c}", "${U:-{p,q}}", "{-2..0}", "..", "{1..}", "$1", "\"{m,n}\""};

    private static List<String> bash(String line) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "set -f; printf '%s\\0' START " + line);
        pb.environment().clear();
        pb.environment().putAll(ENV);
        pb.redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            return null;
        }
        String[] parts = new String(out, StandardCharsets.UTF_8).split("\0", -1);
        List<String> words = new ArrayList<>();
        for (int i = 1; i < parts.length - 1; i++) {
            words.add(parts[i]);
        }
        return words;
    }

    private static boolean bashAvailable() {
        try {
            return bash("a b") != null;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String randomLine(Random r) {
        StringBuilder sb = new StringBuilder();
        int words = 1 + r.nextInt(3);
        for (int w = 0; w < words; w++) {
            if (w > 0) {
                sb.append(' ');
            }
            int frags = 1 + r.nextInt(4);
            for (int f = 0; f < frags; f++) {
                sb.append(FRAGMENTS[r.nextInt(FRAGMENTS.length)]);
            }
        }
        return sb.toString();
    }

    @Test
    void literalLinesAgreeWithBash() throws Exception {
        assumeTrue(bashAvailable(), "bash not available");
        String[] lines = {"{a,b}{1,2}", "{a}{b,c}", "{a,{b,c}", "${U:-{p,q}}", "{$B,c}", "{01..03}", "{08..10}",
            "\"${S}\" ${S}", "x${A}y", "${#A}${#S}", "{1..10..4}", "{10..1..4}", "''\"\"a"};
        for (String line : lines) {
            assertEquals(bash(line), WordExpander.expand(line, ENV), line);
        }
    }

    @Test
    void randomLinesAgreeWithBash() throws Exception {
        assumeTrue(bashAvailable(), "bash not available");
        Random r = new Random(20261125L);
        int ok = 0;
        int errors = 0;
        for (int i = 0; i < 600; i++) {
            String line = randomLine(r);
            List<String> expected = bash(line);
            if (expected == null) {
                errors++;
                assertThrows(ShellSyntaxException.class, () -> WordExpander.expand(line, ENV), line);
            } else {
                ok++;
                assertEquals(expected, WordExpander.expand(line, ENV), line);
            }
        }
        // the generator must exercise both outcomes (measured mix, not asserted tight)
        assertEquals(600, ok + errors);
        assertEquals(true, ok > 150, "too few valid lines: " + ok);
    }

    /** Bad tokens, each starting at the offending character; the tail must not repair them. */
    private static final String[] BAD = {"'", "\"", "${B", "${}", "${1x}", "${B:%}", "'a b", "\"$A"};

    @Test
    void malformedLinesAreRejectedLikeBashAtTheRightPosition() throws Exception {
        assumeTrue(bashAvailable(), "bash not available");
        Random r = new Random(77L);
        for (int i = 0; i < 300; i++) {
            StringBuilder prefix = new StringBuilder();
            int frags = r.nextInt(4);
            for (int f = 0; f < frags; f++) {
                prefix.append(FRAGMENTS[r.nextInt(FRAGMENTS.length)]);
                prefix.append(r.nextBoolean() ? " " : "");
            }
            String line = prefix + BAD[r.nextInt(BAD.length)] + "tail";
            assertEquals(null, bash(line), "bash should reject: " + line);
            ShellSyntaxException e = assertThrows(ShellSyntaxException.class,
                () -> WordExpander.expand(line, ENV), line);
            assertEquals(prefix.length(), e.position(), line);
        }
    }
}
