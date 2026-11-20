package ae.regex;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class DfaTest {

    /** Minimal COMPLETE DFA sizes; the dead state counts whenever some input can reach it. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        // pattern | states | interval classes (maximal intervals of chars with identical behaviour in the minimal DFA)
        "`(a|b)*abb`|5|4",   // q0,q1(a),q2(ab),q3(abb) + dead; intervals: below a, a, b, above b
        "`a*`|2|3",          // loop-on-a, dead; intervals: below a, a, above a (outer two both go dead but are not adjacent)
        "`(a|b)*`|2|3",      // a and b behave alike so [a-b] is one interval: below, [a-b], above
        "`[ab]*`|2|3",
        "`a{3}`|5|3",        // 0,1,2,3 a's + dead
        "``|2|1",            // only the empty string: start accepting, then dead on everything; one interval
        "`[^a]*`|2|3",       // below a, a (dead), above a
        "`.*`|2|9",          // dead reachable because '.' excludes \n \r U+0085 U+2028-2029: 5 excluded intervals + 4 gaps
        "`(a|b)*a(a|b)`|5|4" // second-last char is a: 4 live states + dead; a and b now differ
    })
    void minimalCompleteDfaShape(String pattern, int states, int classes) {
        Dfa d = Regex.compile(pattern).dfa();
        assertEquals(states, d.stateCount(), pattern);
        assertEquals(classes, d.classCount(), pattern);
    }

    @Test
    void universalLanguageIsOneStateOverOneClass() {
        Dfa d = Regex.compile("[\\s\\S]*").dfa();
        assertEquals(1, d.stateCount());
        assertEquals(1, d.classCount());
        assertTrue(d.matches("anything \n at all"));
        assertEquals(d, d.complement().complement().minimize());
        Dfa empty = d.complement().minimize(); // the empty language: one non-accepting state
        assertEquals(1, empty.stateCount());
        assertFalse(empty.matches(""));
        assertTrue(empty.shortestAccepted().isEmpty());
    }

    @Test
    void minimiseIsIdempotentAndCanonicalAcrossSyntax() {
        Dfa x = Regex.compile("(a|b)*").dfa();
        Dfa y = Regex.compile("(a*b*)*").dfa();
        Dfa z = Regex.compile("((a|b)(a|b)*)?").dfa();
        assertEquals(x, y);
        assertEquals(x, z);
        assertEquals(x.hashCode(), y.hashCode());
        assertEquals(x, x.minimize());
        assertNotEquals(x, Regex.compile("a*").dfa());
    }

    @Test
    void unminimisedSubsetConstructionIsLargerButAgrees() {
        String p = "(a|b)*abb";
        Dfa raw = Dfa.fromNfa(Nfa.build(RegexParser.parse(p)));
        Dfa min = raw.minimize();
        assertTrue(raw.stateCount() >= min.stateCount());
        for (String s : new String[] {"", "abb", "aabb", "babb", "ab", "abba", "bbabb"}) {
            assertEquals(min.matches(s), raw.matches(s), s);
        }
    }

    @Test
    void longestMatchEndStopsAtDeadStateAndHonoursEmpty() {
        Dfa d = Regex.compile("a|ab|abc").dfa();
        assertEquals(3, d.longestMatchEnd("abcd", 0));
        assertEquals(-1, d.longestMatchEnd("xabc", 0));
        assertEquals(4, d.longestMatchEnd("xabc", 1));
        Dfa e = Regex.compile("a*").dfa();
        assertEquals(4, e.longestMatchEnd("bbaa", 2));
        assertEquals(0, e.longestMatchEnd("bbaa", 0));
    }

    @Test
    void shortestAcceptedIsDeterministicAndShortest() {
        assertEquals("", Regex.compile("(ab)*").dfa().shortestAccepted().orElseThrow());
        assertEquals("abab", Regex.compile("(ab){2,3}").dfa().shortestAccepted().orElseThrow());
        assertTrue(Regex.compile("[^a-z]{2}").dfa().shortestAccepted().orElseThrow().length() == 2);
    }

    @Test
    void productOperationsHaveBooleanSemantics() {
        Dfa a = Regex.compile("a+").dfa();
        Dfa b = Regex.compile("[ab]{2}").dfa();
        for (String s : new String[] {"", "a", "aa", "ab", "ba", "aaa", "bb", "b"}) {
            boolean ma = a.matches(s);
            boolean mb = b.matches(s);
            assertEquals(ma && mb, a.intersect(b).matches(s), "and " + s);
            assertEquals(ma || mb, a.union(b).matches(s), "or " + s);
            assertEquals(ma && !mb, a.minus(b).matches(s), "minus " + s);
            assertEquals(!ma, a.complement().matches(s), "not " + s);
        }
    }

    @Test
    void equivalentAgreesWithCanonicalEqualityOnHandPickedPairs() {
        String[][] pairs = {
            {"a*", "a+|"}, {"a*", "a+"}, {"(ab)*", "(ab)*(ab)?"}, {"a{0,2}", "|a|aa"}, {"a?a?", "a{0,2}"},
            {"[abc]", "a|b|c"}, {"[abc]", "[a-b]"}, {"(a|b)*b", "(a*b)+"}, {"x*", "x*x*"}};
        for (String[] p : pairs) {
            Dfa l = Regex.compile(p[0]).dfa();
            Dfa r = Regex.compile(p[1]).dfa();
            assertEquals(l.equals(r), l.equivalent(r), p[0] + " vs " + p[1]);
        }
    }

    @Test
    void invalidDfaConstructionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Dfa(new int[] {1}, new int[][] {{0}}, new boolean[] {true}));
        assertThrows(IllegalArgumentException.class, () -> new Dfa(new int[] {0}, new int[][] {{1}}, new boolean[] {true}));
        assertThrows(IllegalArgumentException.class, () -> new Dfa(new int[] {0, 0}, new int[][] {{0, 0}}, new boolean[] {true}));
    }
}
