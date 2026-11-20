package ae.regex;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Differential tests against java.util.regex (full-string matching only) and brute-force oracles.
 * The generator only emits constructs whose full-match semantics are identical in both engines.
 */
class DifferentialTest {

    private static final String ABC = "abc";
    /** One representative per alphabet class that generated patterns can distinguish (a,b,c, high, low, 5 terminators). */
    private static final String REPS = "abcd!\n\r\u0085  ";

    private static String genAtom(Random rnd, int depth) {
        int k = rnd.nextInt(depth > 0 ? 9 : 7);
        return switch (k) {
            case 0, 1, 2 -> String.valueOf(ABC.charAt(rnd.nextInt(3)));
            case 3 -> ".";
            case 4 -> "[ab]";
            case 5 -> "[^a]";
            case 6 -> "[a-c]";
            case 7 -> "(" + genAlt(rnd, depth - 1) + ")";
            default -> "()";
        };
    }

    private static String genPiece(Random rnd, int depth) {
        String atom = genAtom(rnd, depth);
        // a bare quantifier after "()" is fine in both engines
        return switch (rnd.nextInt(10)) {
            case 0 -> atom + "*";
            case 1 -> atom + "+";
            case 2 -> atom + "?";
            case 3 -> atom + "{2}";
            case 4 -> atom + "{1,2}";
            case 5 -> atom + "{0,2}";
            case 6 -> atom + "{2,}";
            default -> atom;
        };
    }

    private static String genCat(Random rnd, int depth) {
        int n = rnd.nextInt(4); // 0..3 pieces; zero pieces gives an empty branch
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(genPiece(rnd, depth));
        }
        return sb.toString();
    }

    private static String genAlt(Random rnd, int depth) {
        String first = genCat(rnd, depth);
        return rnd.nextInt(3) == 0 ? first + "|" + genCat(rnd, depth) : first;
    }

    static String generate(Random rnd) {
        return genAlt(rnd, 2);
    }

    private static List<String> allStrings(String alphabet, int maxLen) {
        List<String> out = new ArrayList<>();
        out.add("");
        int from = 0;
        for (int len = 1; len <= maxLen; len++) {
            int to = out.size();
            for (int i = from; i < to; i++) {
                for (char c : alphabet.toCharArray()) {
                    out.add(out.get(i) + c);
                }
            }
            from = to;
        }
        return out;
    }

    @Test
    void generatorProducesValidPatternsAcceptedByBothEngines() {
        // Guard the generator itself before trusting any comparison built on it.
        Random rnd = new Random(1);
        for (int i = 0; i < 2000; i++) {
            String p = generate(rnd);
            Pattern.compile(p);
            Regex.compile(p);
        }
    }

    @Test
    void nfaDfaMinimisedAndJavaAgreeOnAllShortStrings() {
        Random rnd = new Random(20261120);
        List<String> inputs = allStrings("abc.\n", 5); // '.' and \n exercise the dot / negated-class split
        int accepted = 0;
        int rejected = 0;
        for (int round = 0; round < 150; round++) {
            String p = generate(rnd);
            Pattern oracle = Pattern.compile(p);
            Nfa nfa = Nfa.build(RegexParser.parse(p));
            Dfa raw = Dfa.fromNfa(nfa);
            Dfa min = raw.minimize();
            for (String s : inputs) {
                boolean expected = oracle.matcher(s).matches();
                String msg = "/" + p + "/ on \"" + s.replace("\n", "\\n") + "\"";
                assertEquals(expected, nfa.matches(s), "nfa " + msg);
                assertEquals(expected, raw.matches(s), "dfa " + msg);
                assertEquals(expected, min.matches(s), "min " + msg);
                if (expected) {
                    accepted++;
                } else {
                    rejected++;
                }
            }
        }
        // the corpus must exercise both outcomes substantially, otherwise agreement proves little
        assertTrue(accepted > 3000, "accepted " + accepted);
        assertTrue(rejected > 3000, "rejected " + rejected);
    }

    @Test
    void findIsLeftmostLongestAgainstBruteForce() {
        Random rnd = new Random(99);
        int found = 0;
        for (int round = 0; round < 120; round++) {
            String p = generate(rnd);
            Pattern oracle = Pattern.compile(p);
            Regex r = Regex.compile(p);
            for (int t = 0; t < 12; t++) {
                StringBuilder sb = new StringBuilder();
                int len = rnd.nextInt(9);
                for (int i = 0; i < len; i++) {
                    sb.append("abc\n".charAt(rnd.nextInt(4)));
                }
                String text = sb.toString();
                int from = len == 0 ? 0 : rnd.nextInt(len + 1);
                Regex.Match expected = null;
                search:
                for (int st = from; st <= len; st++) {
                    for (int en = len; en >= st; en--) {
                        if (oracle.matcher(text.substring(st, en)).matches()) {
                            expected = new Regex.Match(st, en);
                            break search;
                        }
                    }
                }
                Optional<Regex.Match> got = r.find(text, from);
                assertEquals(Optional.ofNullable(expected), got, "/" + p + "/ in \"" + text.replace("\n", "\\n") + "\" from " + from);
                if (expected != null) {
                    found++;
                }
            }
        }
        assertTrue(found > 300, "found " + found);
    }

    @Test
    void equivalenceWitnessIsRealAndShortest() {
        Random rnd = new Random(5);
        List<String> shortStrings = allStrings(REPS, 3);
        int equivalent = 0;
        int different = 0;
        for (int round = 0; round < 120; round++) {
            String p = generate(rnd);
            String q = rnd.nextInt(4) == 0 ? p + "|" + generate(rnd) : generate(rnd);
            Regex a = Regex.compile(p);
            Regex b = Regex.compile(q);
            Pattern pa = Pattern.compile(p);
            Pattern pb = Pattern.compile(q);
            Optional<String> w = a.distinguish(b);
            Optional<String> w2 = b.distinguish(a);
            assertEquals(w.isEmpty(), w2.isEmpty(), "symmetry /" + p + "/ /" + q + "/");
            assertEquals(w.isEmpty(), a.equivalent(b));
            assertEquals(w.isEmpty(), a.dfa().equals(b.dfa()), "canonical equality /" + p + "/ /" + q + "/");
            if (w.isPresent()) {
                different++;
                String s = w.get();
                assertNotEquals(pa.matcher(s).matches(), pb.matcher(s).matches(), "witness \"" + s + "\" /" + p + "/ /" + q + "/");
                assertEquals(s.length(), w2.orElseThrow().length(), "shortest length is symmetric");
                if (s.length() <= 3) {
                    for (String t : shortStrings) {
                        if (t.length() < s.length()) {
                            assertEquals(pa.matcher(t).matches(), pb.matcher(t).matches(),
                                    "shorter distinguishing \"" + t + "\" /" + p + "/ /" + q + "/");
                        }
                    }
                }
            } else {
                equivalent++;
                for (String t : shortStrings) {
                    assertEquals(pa.matcher(t).matches(), pb.matcher(t).matches(), "/" + p + "/ /" + q + "/ on " + t);
                }
            }
        }
        // identical-language pairs arise mostly from shared empty/degenerate patterns; still require both kinds
        assertTrue(equivalent >= 3, "equivalent " + equivalent);
        assertTrue(different >= 60, "different " + different);
    }

    @Test
    void knownEquivalentRewritesAreRecognised() {
        // Rewrites known to preserve the language: used to make the "equivalent" branch non-trivial.
        String[][] laws = {
            {"(a|b)*", "(a*b*)*"}, {"a*", "(a*)*"}, {"(a|b)c", "ac|bc"}, {"a{2,}", "aaa*"},
            {"a{1,2}", "a|aa"}, {"(a?){2}", "a{0,2}"}, {"[ab]|c", "[a-c]"}, {"(ab)?", "|ab"}, {"a+a*", "a+"},
            {"(a|b|c)*", "[abc]*"}, {"((a))", "a"}, {"(|a)(|a)", "a{0,2}"}};
        for (String[] law : laws) {
            assertTrue(Regex.compile(law[0]).equivalent(Regex.compile(law[1])), law[0] + " ~ " + law[1]);
            assertEquals(Regex.compile(law[0]).dfa(), Regex.compile(law[1]).dfa(), law[0] + " canonical " + law[1]);
        }
    }

    @Test
    void intersectAndComplementMatchBooleanCombinationsOfJava() {
        Random rnd = new Random(31);
        List<String> inputs = allStrings("abc\n", 4);
        for (int round = 0; round < 80; round++) {
            String p = generate(rnd);
            String q = generate(rnd);
            Pattern pp = Pattern.compile(p);
            Pattern pq = Pattern.compile(q);
            Regex a = Regex.compile(p);
            Regex b = Regex.compile(q);
            Regex and = a.intersect(b);
            Regex not = a.complement();
            for (String s : inputs) {
                boolean x = pp.matcher(s).matches();
                boolean y = pq.matcher(s).matches();
                assertEquals(x && y, and.matches(s), "and /" + p + "/ /" + q + "/ " + s);
                assertEquals(!x, not.matches(s), "not /" + p + "/ " + s);
            }
            // De Morgan at the language level, decided by automata rather than sampling
            Regex lhs = a.intersect(b).complement();
            Regex rhs = a.complement().union(b.complement());
            assertTrue(lhs.equivalent(rhs), "De Morgan /" + p + "/ /" + q + "/");
            assertTrue(a.minus(a).isEmptyLanguage());
        }
    }

    @Test
    void countMatchesEnumerationOverClosedAlphabet() {
        // Patterns using only [ab] / literals a,b accept nothing outside {a,b}, so enumeration over "ab" is complete.
        String[] patterns = {"(a|b)*", "a*b*", "(ab)*", "(a|b){2,3}", "a(a|b)*b", "(a|b)*abb", "[ab]?[ab]?", "(aa|b)*", "b*a{2}b*"};
        for (String p : patterns) {
            Pattern oracle = Pattern.compile(p);
            Regex r = Regex.compile(p);
            for (int n = 0; n <= 10; n++) {
                int len = n;
                long expected = allStrings("ab", n).stream().filter(s -> s.length() == len).filter(s -> oracle.matcher(s).matches()).count();
                assertEquals(BigInteger.valueOf(expected), r.count(n), "/" + p + "/ n=" + n);
            }
        }
    }

    @Test
    void exampleIsAcceptedByJavaWheneverTheLanguageIsNonEmpty() {
        Random rnd = new Random(77);
        int nonEmpty = 0;
        for (int round = 0; round < 200; round++) {
            String p = generate(rnd);
            Regex r = Regex.compile(p);
            Optional<String> ex = r.example();
            assertEquals(!r.isEmptyLanguage(), ex.isPresent());
            if (ex.isPresent()) {
                nonEmpty++;
                assertTrue(Pattern.matches(p, ex.get()), "/" + p + "/ example \"" + ex.get() + "\"");
            }
        }
        assertTrue(nonEmpty > 150, "nonEmpty " + nonEmpty);
    }
}
