package ae.minisql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LikeTest {
    @Test
    void literalCases() {
        assertTrue(LikeMatcher.matches("", ""));
        assertTrue(LikeMatcher.matches("", "%"));
        assertFalse(LikeMatcher.matches("", "_"));
        assertTrue(LikeMatcher.matches("abc", "a_c"));
        assertTrue(LikeMatcher.matches("abc", "%b%"));
        assertFalse(LikeMatcher.matches("abc", "%d%"));
        assertTrue(LikeMatcher.matches("aXbXc", "a%b%c"));
        assertFalse(LikeMatcher.matches("ab", "a%b%c"));
        assertTrue(LikeMatcher.matches("a.c", "a.c"));
        assertFalse(LikeMatcher.matches("abc", "a.c")); // '.' is literal, not a regex dot
        assertTrue(LikeMatcher.matches("a(b", "a(b"));  // regex metacharacters are literals
        assertFalse(LikeMatcher.matches("ABC", "abc")); // case sensitive
    }

    @Test
    void agreesWithRegexOracleOnRandomInputs() {
        Random rnd = new Random(7);
        String alpha = "ab.(";
        int matches = 0;
        for (int i = 0; i < 20000; i++) {
            StringBuilder t = new StringBuilder();
            for (int n = rnd.nextInt(6); n > 0; n--) t.append(alpha.charAt(rnd.nextInt(alpha.length())));
            StringBuilder p = new StringBuilder();
            StringBuilder re = new StringBuilder();
            for (int n = rnd.nextInt(6); n > 0; n--) {
                int c = rnd.nextInt(6);
                if (c == 0) { p.append('%'); re.append(".*"); }
                else if (c == 1) { p.append('_'); re.append('.'); }
                else { char ch = alpha.charAt(rnd.nextInt(alpha.length())); p.append(ch); re.append(Pattern.quote(String.valueOf(ch))); }
            }
            boolean want = Pattern.compile(re.toString(), Pattern.DOTALL).matcher(t).matches();
            if (want) matches++;
            assertEquals(want, LikeMatcher.matches(t.toString(), p.toString()), "text=" + t + " pattern=" + p);
        }
        assertTrue(matches > 2000 && matches < 18000, "outcome mix " + matches);
    }

    @Test
    void manyPercentsDoNotBlowUp() {
        String text = "a".repeat(200) + "b";
        String pattern = "%a".repeat(30) + "%c";
        long t0 = System.nanoTime();
        assertFalse(LikeMatcher.matches(text, pattern));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000, "pattern matching must be polynomial");
    }
}
