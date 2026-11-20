package ae.peg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Oracle: java.util.regex. PEG semantics equal a regex in which every unit is atomic: choice = {@code (?>A|B)},
 * repetition = possessive quantifier over an atomic body, predicates = lookahead. The generator tracks which
 * grammars the analyser accepts; the outcome mix is asserted so the test cannot pass vacuously.
 */
class PegDifferentialTest {
    private static final String ALPHA = "abc";

    private static String hex(char c) {
        return String.format("\\u%04x", (int) c);
    }

    /** Token-by-token translation; each construct is a self-contained atomic group. */
    static String toRegex(Expr e) {
        return switch (e) {
            case Expr.Lit l -> "(?>" + Pattern.quote(l.text()) + ")";
            case Expr.Any a -> "(?>[\\s\\S])";
            case Expr.CharClass c -> {
                StringBuilder sb = new StringBuilder("(?>[").append(c.negated() ? "^" : "");
                for (Expr.Range r : c.ranges()) {
                    sb.append(hex(r.lo())).append('-').append(hex(r.hi()));
                }
                yield sb.append("])").toString();
            }
            case Expr.Seq s -> {
                StringBuilder sb = new StringBuilder("(?>");
                s.items().forEach(x -> sb.append(toRegex(x)));
                yield sb.append(")").toString();
            }
            case Expr.Choice c -> {
                List<String> parts = new ArrayList<>();
                c.alts().forEach(x -> parts.add(toRegex(x)));
                yield "(?>" + String.join("|", parts) + ")";
            }
            case Expr.Repeat r -> "(?>(?:" + toRegex(r.body()) + ")" + (r.atLeastOne() ? "++" : "*+") + ")";
            case Expr.Opt o -> "(?>(?:" + toRegex(o.body()) + ")?+)";
            case Expr.Pred p -> (p.negative() ? "(?!" : "(?=") + toRegex(p.body()) + ")";
            case Expr.Ref r -> throw new IllegalArgumentException("refs are not generated here");
        };
    }

    static Expr gen(Random rnd, int depth) {
        int pick = depth <= 0 ? rnd.nextInt(3) : rnd.nextInt(11);
        switch (pick) {
            case 0:
                return new Expr.Lit(rnd.nextBoolean() ? String.valueOf(ALPHA.charAt(rnd.nextInt(3)))
                        : "" + ALPHA.charAt(rnd.nextInt(3)) + ALPHA.charAt(rnd.nextInt(3)));
            case 1:
                return new Expr.Any();
            case 2: {
                char lo = ALPHA.charAt(rnd.nextInt(3));
                char hi = (char) (lo + rnd.nextInt('c' - lo + 1));
                return new Expr.CharClass(rnd.nextInt(4) == 0, List.of(new Expr.Range(lo, hi)));
            }
            case 3:
            case 4:
                return Expr.seq(genList(rnd, depth, 2 + rnd.nextInt(2)));
            case 5:
            case 6:
                return Expr.choice(genList(rnd, depth, 2 + rnd.nextInt(2)));
            case 7:
                return new Expr.Repeat(gen(rnd, depth - 1), rnd.nextBoolean());
            case 8:
                return new Expr.Opt(gen(rnd, depth - 1));
            default:
                return new Expr.Pred(gen(rnd, depth - 1), rnd.nextBoolean());
        }
    }

    private static List<Expr> genList(Random rnd, int depth, int n) {
        List<Expr> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(gen(rnd, depth - 1));
        }
        return out;
    }

    @Test
    void randomExpressionsAgreeWithAtomicRegexOracle() {
        Random rnd = new Random(20261120);
        int accepted = 0;
        int rejected = 0;
        int matched = 0;
        int unmatched = 0;
        int emptyMatches = 0;
        int checked = 0;
        for (int i = 0; i < 1500; i++) {
            Expr e = gen(rnd, 4);
            Grammar g;
            try {
                g = Grammar.of(Map.of("S", e));
            } catch (GrammarException ex) {
                rejected++;
                continue;
            }
            accepted++;
            Pattern oracle = Pattern.compile(toRegex(e));
            PegParser p = new PegParser(g);
            PegParser plain = new PegParser(g, false);
            for (int k = 0; k < 12; k++) {
                StringBuilder in = new StringBuilder();
                int len = rnd.nextInt(9);
                for (int j = 0; j < len; j++) {
                    in.append((char) ('a' + rnd.nextInt(4)));
                }
                String input = in.toString();
                Matcher m = oracle.matcher(input);
                int expected = m.lookingAt() ? m.end() : -1;
                Optional<Node> got = p.matchPrefix(input);
                String ctx = "grammar [" + Expr.render(e) + "] input [" + input + "] regex " + oracle.pattern();
                assertEquals(expected, got.map(Node::end).orElse(-1), ctx);
                assertEquals(got, plain.matchPrefix(input), ctx);
                checked++;
                if (expected >= 0) {
                    matched++;
                    if (expected == 0) {
                        emptyMatches++;
                    }
                } else {
                    unmatched++;
                }
                // render/parse round trip of the generated expression
                assertEquals(g.rules(), Grammar.parse(g.toString()).rules(), ctx);
            }
        }
        // outcome mix measured by a first run; thresholds sit well under the observed counts
        assertTrue(accepted >= 600, "accepted=" + accepted + " rejected=" + rejected);
        assertTrue(rejected >= 20, "rejected=" + rejected);
        assertTrue(matched >= 1500 && unmatched >= 1500, "matched=" + matched + " unmatched=" + unmatched);
        assertTrue(emptyMatches >= 100, "emptyMatches=" + emptyMatches);
        assertEquals(accepted * 12, checked);
    }

    @Test
    void mutatedJsonDocumentsGiveIdenticalResultsAndErrorsWithAndWithoutMemo() {
        Grammar g = Grammar.parse(PegGrammarsTest.JSONISH);
        PegParser memo = new PegParser(g, true);
        PegParser plain = new PegParser(g, false);
        String base = "{\"a\": [true, {\"b\": null, \"c\": -1.5}], \"d\": \"x\"}";
        String junk = "{}[],:\"\\ tn01-.x";
        Random rnd = new Random(7);
        int valid = 0;
        int invalid = 0;
        for (int i = 0; i < 400; i++) {
            StringBuilder sb = new StringBuilder(base);
            int edits = rnd.nextInt(3);
            for (int k = 0; k < edits; k++) {
                int pos = rnd.nextInt(sb.length());
                switch (rnd.nextInt(3)) {
                    case 0 -> sb.deleteCharAt(pos);
                    case 1 -> sb.insert(pos, junk.charAt(rnd.nextInt(junk.length())));
                    default -> sb.setCharAt(pos, junk.charAt(rnd.nextInt(junk.length())));
                }
            }
            String in = sb.toString();
            PegParser.Result a = memo.run(in);
            PegParser.Result b = plain.run(in);
            assertEquals(b.node(), a.node(), in);
            assertEquals(b.farthest(), a.farthest(), in);
            assertEquals(b.expected(), a.expected(), in);
            boolean ok = a.node().isPresent() && a.node().get().end() == in.length();
            if (ok) {
                valid++;
            } else {
                invalid++;
                assertTrue(a.farthest() >= 0 || a.node().isPresent(), in);
            }
        }
        assertTrue(valid >= 30 && invalid >= 150, "valid=" + valid + " invalid=" + invalid);
    }
}
