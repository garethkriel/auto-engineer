package glob;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GlobTest {
    private static boolean m(String p, String s) {
        return Glob.compile(p).matches(s);
    }

    @ParameterizedTest
    @CsvSource({
        "abc,abc,true",
        "abc,abd,false",
        "abc,abcd,false",
        "'',,true",
        "a?c,abc,true",
        "a?c,ac,false",
        "a?c,a/c,false",
        "*.java,Foo.java,true",
        "*.java,.java,true",
        "*.java,src/Foo.java,false",
        "src/*/Foo.java,src/a/Foo.java,true",
        "src/*/Foo.java,src/a/b/Foo.java,false",
        "**,a/b/c,true",
        "**,'',true",
        "a/**,a/,true",
        "a/**,a/b/c,true",
        "a/**,a,false",
        "**/Foo.java,Foo.java,true",
        "**/Foo.java,a/b/Foo.java,true",
        "**/Foo.java,a/b/XFoo.java,false",
        "a/**/b,a/b,true",
        "a/**/b,a/x/y/b,true",
        "a/**/b,a/xb,false",
        "a**b,a/b,true",
        "a**b,axxb,true",
        "[abc]x,bx,true",
        "[abc]x,dx,false",
        "[a-c]x,bx,true",
        "[a-c]x,dx,false",
        "[!a-c]x,dx,true",
        "[!a-c]x,bx,false",
        "[!a]x,/x,false",
        "[]]x,]x,true",
        "[!]]x,ax,true",
        "[a-]x,-x,true",
        "\\*,*,true",
        "\\*,a,false",
        "a\\?b,a?b,true",
        "a\\?b,axb,false",
        "\\[a],[a],true",
    })
    void table(String pattern, String path, boolean expected) {
        String p = pattern == null ? "" : pattern;
        String s = path == null ? "" : path;
        assertEquals(expected, m(p, s), "pattern=" + p + " path=" + s);
    }

    @Test
    void starFollowedByLiteralBacktracks() {
        assertTrue(m("*ab*ab", "xabyabab"));
        assertFalse(m("*ab*ab", "xabyab_"));
    }

    @Test
    void pathologicalPatternIsFast() {
        String path = "a".repeat(5000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertFalse(m("*a*a*a*a*a*b", path)));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(m("*a*a*a*a*a", path)));
    }

    @Test
    void doubleStarSlashOnlySpecialAtSegmentStart() {
        // 'x**/b' : '**' is not at a segment start, so it is a plain '**' then literal '/b'
        assertTrue(m("x**/b", "xyz/b"));
        assertTrue(m("x**/b", "x/q/b"));
        assertFalse(m("x**/b", "xb"));
    }

    @Test
    void consecutiveDirectoryWildcards() {
        assertTrue(m("**/**/a", "a"));
        assertTrue(m("**/**/a", "x/y/a"));
        assertFalse(m("**/**/a", "x/y/b"));
    }

    @Test
    void invalidPatternsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Glob.compile(null));
        assertThrows(IllegalArgumentException.class, () -> Glob.compile("[abc"));
        assertThrows(IllegalArgumentException.class, () -> Glob.compile("[]"));
        assertThrows(IllegalArgumentException.class, () -> Glob.compile("[!]"));
        assertThrows(IllegalArgumentException.class, () -> Glob.compile("[z-a]"));
        assertThrows(IllegalArgumentException.class, () -> Glob.compile("abc\\"));
    }

    @Test
    void nullPathRejected() {
        assertThrows(NullPointerException.class, () -> Glob.compile("a").matches(null));
    }

    @Test
    void compiledGlobIsReusableAndExposesPattern() {
        Glob g = Glob.compile("*.txt");
        assertTrue(g.matches("a.txt"));
        assertFalse(g.matches("a.md"));
        assertTrue(g.matches("b.txt"));
        assertEquals("*.txt", g.pattern());
        assertEquals("Glob[*.txt]", g.toString());
    }

    @Test
    void matchesAgainstSimpleReferenceOnRandomInputs() {
        java.util.Random rnd = new java.util.Random(42);
        String patAlphabet = "ab*?";
        String strAlphabet = "ab";
        for (int i = 0; i < 3000; i++) {
            StringBuilder p = new StringBuilder();
            for (int k = rnd.nextInt(7); k > 0; k--) p.append(patAlphabet.charAt(rnd.nextInt(4)));
            StringBuilder s = new StringBuilder();
            for (int k = rnd.nextInt(8); k > 0; k--) s.append(strAlphabet.charAt(rnd.nextInt(2)));
            // no '/' present, so '*' and '**' both mean "any run", '?' one char: compare to java regex
            // translate char by char: a naive replace turns "*?" into the lazy quantifier ".*?"
            StringBuilder rx = new StringBuilder();
            for (char ch : p.toString().toCharArray()) {
                if (ch == '*') {
                    if (rx.length() < 2 || !rx.substring(rx.length() - 2).equals(".*")) rx.append(".*");
                } else if (ch == '?') {
                    rx.append("(?:.)");
                } else {
                    rx.append(ch);
                }
            }
            String regex = rx.toString();
            assertEquals(s.toString().matches(regex), m(p.toString(), s.toString()),
                    "pattern=" + p + " path=" + s);
        }
    }
}
