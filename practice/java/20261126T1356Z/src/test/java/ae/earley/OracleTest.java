package ae.earley;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OracleTest {

    static String randomGrammar(Random rnd) {
        int k = 1 + rnd.nextInt(3);
        StringBuilder sb = new StringBuilder();
        for (int a = 0; a < k; a++) {
            int alts = 1 + rnd.nextInt(3);
            sb.append('N').append(a).append(" ->");
            for (int alt = 0; alt < alts; alt++) {
                if (alt > 0) {
                    sb.append(" |");
                }
                int len = rnd.nextInt(4);
                for (int s = 0; s < len; s++) {
                    if (rnd.nextInt(100) < 55) {
                        sb.append(" '").append(rnd.nextBoolean() ? 'a' : 'b').append('\'');
                    } else {
                        sb.append(" N").append(rnd.nextInt(k));
                    }
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static List<String> allStrings(int maxLen) {
        List<String> out = new ArrayList<>(List.of(""));
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (s.length() < maxLen) {
                out.add(s + "a");
                out.add(s + "b");
            }
        }
        return out;
    }

    /** Known-answer check of the oracle itself, so a broken oracle cannot bless a broken parser. */
    @Test
    void oracleKnownAnswers() {
        Grammar amb = Grammar.parse("E -> E '+' E | 'n'");
        assertEquals(Optional.of(BigInteger.valueOf(5)), new Oracle(amb, "n+n+n+n").count());
        assertEquals(Optional.of(BigInteger.ZERO), new Oracle(amb, "n+").count());
        Grammar cyc = Grammar.parse("S -> S | 'a'");
        assertEquals(Optional.empty(), new Oracle(cyc, "a").count());
        assertEquals(Optional.of(BigInteger.ZERO), new Oracle(cyc, "b").count());
        Grammar nul = Grammar.parse("S -> A A\nA -> 'a' |");
        assertEquals(Optional.of(BigInteger.TWO), new Oracle(nul, "a").count());
        Grammar dead = Grammar.parse("S -> 'a' | 'b' T\nT -> T | 'c'");
        assertEquals(Optional.of(BigInteger.ONE), new Oracle(dead, "a").count());
        assertEquals(Optional.empty(), new Oracle(dead, "bc").count());
    }

    @Test
    void generatorProducesValidGrammarsWithAllShapes() {
        Random rnd = new Random(7);
        int nullable = 0;
        int cyclic = 0;
        int ambiguous = 0;
        int multi = 0;
        for (int t = 0; t < 300; t++) {
            Grammar g = Grammar.parse(randomGrammar(rnd)); // must not throw
            if (!g.nullable().isEmpty()) {
                nullable++;
            }
            if (g.nonTerminals().size() > 1) {
                multi++;
            }
            for (String s : allStrings(3)) {
                Optional<BigInteger> c = new Oracle(g, s).count();
                if (c.isEmpty()) {
                    cyclic++;
                } else if (c.get().compareTo(BigInteger.ONE) > 0) {
                    ambiguous++;
                }
            }
        }
        assertTrue(nullable > 30, "nullable grammars " + nullable);
        assertTrue(multi > 100, "multi " + multi);
        assertTrue(cyclic > 20, "cyclic parses " + cyclic);
        assertTrue(ambiguous > 100, "ambiguous parses " + ambiguous);
    }

    @Test
    void recogniserCountAndTreesAgreeWithOracleOnRandomGrammars() {
        Random rnd = new Random(20261126);
        List<String> inputs = allStrings(4);
        int accepted = 0;
        int rejected = 0;
        int infinite = 0;
        for (int t = 0; t < 300; t++) {
            String text = randomGrammar(rnd);
            Grammar g = Grammar.parse(text);
            EarleyParser p = new EarleyParser(g);
            for (String s : inputs) {
                Oracle o = new Oracle(g, s);
                ParseResult r = p.parse(s);
                String ctx = "grammar:\n" + text + "input: '" + s + "'";
                assertEquals(o.accepts(), r.accepted(), ctx);
                assertEquals(o.count(), r.treeCount(), ctx);
                if (!r.accepted()) {
                    rejected++;
                    assertEquals(List.of(), r.trees(5), ctx);
                    continue;
                }
                accepted++;
                Optional<BigInteger> count = r.treeCount();
                List<Tree> trees = r.trees(200);
                assertFalse(trees.isEmpty(), ctx);
                Set<Tree> seen = new HashSet<>(); // records compare rule indexes, so duplicate rules stay distinct
                for (Tree tree : trees) {
                    assertEquals(List.of(), Oracle.violations(g, tree, s), ctx + "\ntree " + tree);
                    assertTrue(seen.add(tree), ctx + "\nduplicate " + tree);
                }
                if (count.isPresent()) {
                    assertEquals(count.get().min(BigInteger.valueOf(200)).intValueExact(), trees.size(), ctx);
                } else {
                    infinite++;
                }
            }
        }
        // Measured mix for seed 20261126: accepted=785 rejected=8515 infinite=150 (of 9300); floors are ~half.
        assertTrue(accepted > 400, "accepted " + accepted);
        assertTrue(rejected > 5000, "rejected " + rejected);
        assertTrue(infinite > 70, "infinite " + infinite);
    }

    @Test
    void rejectionOffsetIsADeadEnd() {
        Random rnd = new Random(99);
        List<String> inputs = allStrings(4);
        List<String> suffixes = allStrings(3);
        int dead = 0;
        for (int t = 0; t < 120; t++) {
            Grammar g = Grammar.parse(randomGrammar(rnd));
            EarleyParser p = new EarleyParser(g);
            for (String s : inputs) {
                ParseResult r = p.parse(s);
                if (r.accepted()) {
                    assertEquals(-1, r.errorOffset());
                    continue;
                }
                int e = r.errorOffset();
                assertTrue(e >= 0 && e <= s.length(), "offset " + e + " for '" + s + "'");
                if (e < s.length()) {
                    dead++;
                    String prefix = s.substring(0, e + 1); // includes the symbol nobody could consume
                    for (String suffix : suffixes) {
                        assertFalse(new Oracle(g, prefix + suffix).accepts(),
                            "prefix '" + prefix + "' + '" + suffix + "' accepted although offset=" + e);
                    }
                    assertFalse(r.expected().contains((int) s.charAt(e)));
                }
            }
        }
        assertTrue(dead > 500, "dead-end cases " + dead);
    }
}
