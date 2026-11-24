package ae.aho;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AhoCorasickTest {

    private static Match m(int s, int e, int p) {
        return new Match(s, e, p);
    }

    private static AhoCorasick build(boolean ic, String... patterns) {
        AhoCorasick ac = AhoCorasick.build(List.of(patterns), ic);
        ac.checkInvariants();
        return ac;
    }

    // ---------- brute-force oracles ----------

    private static boolean eq(char a, char b, boolean ic) {
        return ic ? Character.toLowerCase(Character.toUpperCase(a)) == Character.toLowerCase(Character.toUpperCase(b)) : a == b;
    }

    private static List<Match> oracleAll(List<String> pats, String text, boolean ic) {
        List<Match> r = new ArrayList<>();
        for (int s = 0; s < text.length(); s++) {
            for (int p = 0; p < pats.size(); p++) {
                String pat = pats.get(p);
                if (s + pat.length() <= text.length() && text.regionMatches(ic, s, pat, 0, pat.length())) {
                    r.add(m(s, s + pat.length(), p));
                }
            }
        }
        r.sort(Comparator.comparingInt(Match::end).thenComparingInt(Match::start).thenComparingInt(Match::pattern));
        return r;
    }

    private static List<Match> oracleLeftmostLongest(List<String> pats, String text, boolean ic) {
        List<Match> r = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int bestLen = 0;
            int bestP = -1;
            for (int p = 0; p < pats.size(); p++) {
                String pat = pats.get(p);
                if (pat.length() > bestLen && i + pat.length() <= text.length()
                        && text.regionMatches(ic, i, pat, 0, pat.length())) {
                    bestLen = pat.length();
                    bestP = p;
                }
            }
            if (bestP >= 0) {
                r.add(m(i, i + bestLen, bestP));
                i += bestLen;
            } else {
                i++;
            }
        }
        return r;
    }

    private static String randomString(Random rnd, int maxLen, int alphabet) {
        int len = rnd.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + rnd.nextInt(alphabet)));
        }
        return sb.toString();
    }

    private static List<String> randomPatterns(Random rnd, int count, int maxLen, int alphabet) {
        List<String> pats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pats.add(randomString(rnd, maxLen - 1, alphabet) + (char) ('a' + rnd.nextInt(alphabet)));
        }
        return pats;
    }

    // ---------- literal known answers ----------

    @Test
    void classicUshers() {
        AhoCorasick ac = build(false, "he", "she", "his", "hers");
        // she [1,4) p1, he [2,4) p0 (same end, start ascending), hers [2,6) p3
        assertEquals(List.of(m(1, 4, 1), m(2, 4, 0), m(2, 6, 3)), ac.findAll("ushers"));
        assertEquals(List.of(m(1, 4, 1)), ac.leftmostLongest("ushers"));
        assertEquals("uXrs", ac.replaceAll("ushers", List.of("-", "X", "-", "-")));
        assertArrayEquals(new int[] {1, 1, 0, 1}, ac.countPerPattern("ushers"));
    }

    @Test
    void overlapsAndRuns() {
        AhoCorasick ac = build(false, "aa");
        assertEquals(List.of(m(0, 2, 0), m(1, 3, 0), m(2, 4, 0)), ac.findAll("aaaa"));
        assertEquals(List.of(m(0, 2, 0), m(2, 4, 0)), ac.leftmostLongest("aaaa"));
        AhoCorasick nested = build(false, "a", "aa", "aaa");
        assertEquals(List.of(m(0, 3, 2), m(3, 4, 0)), nested.leftmostLongest("aaaa"));
        // 4 + 3 + 2 occurrences of a, aa, aaa in "aaaa"
        assertArrayEquals(new int[] {4, 3, 2}, nested.countPerPattern("aaaa"));
    }

    @Test
    void duplicatePatternsAreEachReported() {
        AhoCorasick ac = build(false, "ab", "ab");
        assertEquals(List.of(m(0, 2, 0), m(0, 2, 1)), ac.findAll("ab"));
        assertEquals(List.of(m(0, 2, 0)), ac.leftmostLongest("ab"));
    }

    @Test
    void patternThatIsSuffixOfAnotherViaOutputLink() {
        // "bc" is reached only through the output link of "abc"; "c" through the link of "bc".
        AhoCorasick ac = build(false, "abc", "bc", "c");
        assertEquals(List.of(m(0, 3, 0), m(1, 3, 1), m(2, 3, 2)), ac.findAll("abc"));
    }

    @Test
    void failureRecoversAfterPartialMatch() {
        AhoCorasick ac = build(false, "abcd", "bcx");
        assertEquals(List.of(m(1, 4, 1)), ac.findAll("abcbcx".substring(0, 1) + "bcx"));
        assertEquals(List.of(m(3, 6, 1)), ac.findAll("abcbcx"));
    }

    @Test
    void ignoreCase() {
        AhoCorasick ac = build(true, "HeLLo");
        assertEquals(List.of(m(4, 9, 0), m(10, 15, 0)), ac.findAll("say hello HELLO"));
        AhoCorasick exact = build(false, "HeLLo");
        assertTrue(exact.findAll("say hello HELLO").isEmpty());
    }

    @Test
    void emptyTextAndNoPatterns() {
        AhoCorasick ac = build(false, "x");
        assertTrue(ac.findAll("").isEmpty());
        AhoCorasick none = AhoCorasick.build(List.of(), false);
        none.checkInvariants();
        assertTrue(none.findAll("anything").isEmpty());
        assertEquals("anything", none.replaceAll("anything", List.of()));
        assertEquals(1, none.nodeCount());
    }

    @Test
    void invalidArguments() {
        var e = assertThrows(IllegalArgumentException.class, () -> AhoCorasick.build(List.of("a", "", "c"), false));
        assertTrue(e.getMessage().contains("1"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AhoCorasick.build(null, false));
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> AhoCorasick.build(withNull, false));
        AhoCorasick ac = build(false, "a", "b");
        assertThrows(IllegalArgumentException.class, () -> ac.replaceAll("ab", List.of("x")));
    }

    @Test
    void sharedPrefixesShareNodes() {
        // root + a,ab,abc + abd + b = 6 nodes
        assertEquals(6, build(false, "abc", "abd", "ab", "b").nodeCount());
    }

    // ---------- stream ----------

    @Test
    void streamMatchSpanningChunks() {
        AhoCorasick ac = build(false, "hello", "lo w");
        AhoCorasick.Stream s = ac.stream();
        assertTrue(s.feed("hel").isEmpty());
        assertEquals(List.of(m(0, 5, 0)), s.feed("lo"));
        assertEquals(List.of(m(3, 7, 1)), s.feed(" w"));
        assertEquals(7, s.position());
        s.reset();
        assertEquals(0, s.position());
        assertTrue(s.feed("lo").isEmpty());
    }

    @Test
    void resetForgetsPartialMatch() {
        AhoCorasick ac = build(false, "hello");
        AhoCorasick.Stream s = ac.stream();
        assertTrue(s.feed("hel").isEmpty());
        s.reset();
        assertTrue(s.feed("lo").isEmpty(), "stale automaton state survived reset");
        s.reset();
        assertEquals(List.of(m(0, 5, 0)), s.feed("hello"));
    }

    // ---------- invariants ----------

    @Test
    void checkerDetectsCorruption() {
        AhoCorasick a = build(false, "abc", "bc", "c");
        a.fail[3] = 3; // node 3 = "abc": fail link to itself
        assertThrows(IllegalStateException.class, a::checkInvariants);
        AhoCorasick b = build(false, "abc", "bc", "c");
        b.fail[3] = 1; // "a" is not a suffix of "abc"
        assertThrows(IllegalStateException.class, b::checkInvariants);
        AhoCorasick c = build(false, "abc", "bc", "c");
        c.out[3] = 0; // should point at "bc"
        assertThrows(IllegalStateException.class, c::checkInvariants);
        AhoCorasick d = build(false, "abc", "bc", "c");
        d.depth[2] = 7;
        assertThrows(IllegalStateException.class, d::checkInvariants);
    }

    // ---------- randomized differential tests ----------

    @Test
    void randomizedAgainstBruteForce() {
        Random rnd = new Random(20261124);
        int withMatches = 0;
        for (int round = 0; round < 400; round++) {
            boolean ic = rnd.nextBoolean();
            int alphabet = 2 + rnd.nextInt(3);
            List<String> pats = randomPatterns(rnd, rnd.nextInt(8), 5, alphabet);
            String text = randomString(rnd, 40, alphabet);
            if (ic) {
                text = randomCase(rnd, text);
            }
            AhoCorasick ac = AhoCorasick.build(pats, ic);
            ac.checkInvariants();
            List<Match> expected = oracleAll(pats, text, ic);
            String msg = "pats=" + pats + " text=" + text + " ic=" + ic;
            assertEquals(expected, ac.findAll(text), msg);
            assertEquals(oracleLeftmostLongest(pats, text, ic), ac.leftmostLongest(text), msg);
            int[] counts = new int[pats.size()];
            for (Match x : expected) {
                counts[x.pattern()]++;
            }
            assertArrayEquals(counts, ac.countPerPattern(text), msg);
            List<String> reps = new ArrayList<>();
            for (int i = 0; i < pats.size(); i++) {
                reps.add("<" + i + ">");
            }
            assertEquals(oracleReplace(pats, reps, text, ic), ac.replaceAll(text, reps), msg);
            if (!expected.isEmpty()) {
                withMatches++;
            }
        }
        assertTrue(withMatches > 150, "only " + withMatches + " of 400 rounds had matches");
    }

    private static String randomCase(Random rnd, String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(rnd.nextBoolean() ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }

    private static String oracleReplace(List<String> pats, List<String> reps, String text, boolean ic) {
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (Match x : oracleLeftmostLongest(pats, text, ic)) {
            sb.append(text, last, x.start()).append(reps.get(x.pattern()));
            last = x.end();
        }
        return sb.append(text.substring(last)).toString();
    }

    @Test
    void randomizedStreamChunking() {
        Random rnd = new Random(77);
        for (int round = 0; round < 300; round++) {
            int alphabet = 2 + rnd.nextInt(2);
            List<String> pats = randomPatterns(rnd, 1 + rnd.nextInt(6), 6, alphabet);
            String text = randomString(rnd, 60, alphabet);
            AhoCorasick ac = AhoCorasick.build(pats, false);
            AhoCorasick.Stream stream = ac.stream();
            List<Match> got = new ArrayList<>();
            int pos = 0;
            while (pos < text.length()) {
                int len = rnd.nextInt(5); // zero-length chunks included
                int end = Math.min(text.length(), pos + len);
                got.addAll(stream.feed(text.substring(pos, end)));
                pos = end;
            }
            got.addAll(stream.feed(""));
            assertEquals(oracleAll(pats, text, false), got, "pats=" + pats + " text=" + text);
            assertEquals(text.length(), stream.position());
        }
    }

    @Test
    void largeInputStaysLinear() {
        // Worst case for naive matching: pattern a^k b over a long run of a's.
        StringBuilder pat = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            pat.append('a');
        }
        AhoCorasick ac = build(false, pat + "b", "aab");
        String text = "a".repeat(400_000) + "b";
        long t0 = System.nanoTime();
        List<Match> r = ac.findAll(text);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // text length 400001; a^2000 b has length 2001 -> start 398000; aab has length 3 -> start 399998.
        // Same end, so the earlier start (longer pattern, index 0) comes first.
        assertEquals(List.of(m(398_000, 400_001, 0), m(399_998, 400_001, 1)), r);
        assertTrue(ms < 3000, "took " + ms + " ms");
    }
}
