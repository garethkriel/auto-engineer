package ae.parsegen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Cross-engine checks: enumeration oracle vs Earley, then Earley vs LL(1) vs SLR(1) on equivalent grammars. */
class DifferentialTest {
    private static List<List<String>> allStrings(Set<String> alphabet, int maxLen) {
        List<String> sym = new ArrayList<>(alphabet);
        List<List<String>> out = new ArrayList<>();
        out.add(List.of());
        int from = 0;
        for (int len = 1; len <= maxLen; len++) {
            int to = out.size();
            for (int i = from; i < to; i++) {
                for (String s : sym) {
                    List<String> w = new ArrayList<>(out.get(i));
                    w.add(s);
                    out.add(w);
                }
            }
            from = to;
        }
        return out;
    }

    @Test
    void earleyAgreesWithSentenceEnumerationOnAllShortStrings() {
        String[] texts = {Fx.LR, Fx.AMB, Fx.SB, Fx.LIST, Fx.ELSE, Fx.LVAL};
        int[] maxLen = {6, 7, 7, 7, 7, 6};
        for (int k = 0; k < texts.length; k++) {
            Grammar g = Grammar.parse(texts[k]);
            Set<List<String>> language = Generator.enumerate(g, maxLen[k]);
            assertFalse(language.isEmpty(), texts[k]);
            for (List<String> w : allStrings(g.terminals(), maxLen[k])) {
                assertEquals(language.contains(w), Earley.recognizes(g, w), texts[k] + " on " + w);
            }
        }
    }

    @Test
    void enumerationHasKnownCounts() {
        // a^n c b^n has one sentence per odd length 2n+1 <= 9: 1,3,5,7,9 -> 5 sentences
        assertEquals(5, Generator.enumerate(Grammar.parse(Fx.SB), 9).size());
        // id (, id)* . has one sentence per length 2,4,6
        assertEquals(3, Generator.enumerate(Grammar.parse(Fx.LIST), 6).size());
        // E -> E + E | a: sentences a (+ a)^k of length 2k+1; ambiguity must not duplicate them
        assertEquals(List.of(List.of("a"), List.of("a", "+", "a"), List.of("a", "+", "a", "+", "a")),
                new ArrayList<>(Generator.enumerate(Grammar.parse(Fx.AMB), 5)));
        assertThrows(IllegalArgumentException.class, () -> Generator.enumerate(Grammar.parse(Fx.LL), 3));
    }

    @Test
    void enumerationTerminatesOnUnitCycles() {
        // A -> B -> A -> ... never grows the form; only the visited set stops the loop
        assertEquals(List.of(List.of("a")), new ArrayList<>(Generator.enumerate(Grammar.parse("A -> B | a\nB -> A\n"), 4)));
    }

    @Test
    void randomSentencesBelongToTheLanguage() {
        for (String text : List.of(Fx.LL, Fx.LR, Fx.AB, Fx.SB, Fx.AMB, Fx.LLNOTSLR)) {
            Grammar g = Grammar.parse(text);
            Random rnd = new Random(7);
            Set<List<String>> distinct = new LinkedHashSet<>();
            for (int i = 0; i < 200; i++) {
                List<String> w = Generator.random(g, rnd, 12);
                assertTrue(Earley.recognizes(g, w), text + " generated " + w);
                distinct.add(w);
            }
            // Fx.LLNOTSLR has exactly two sentences ("a b", "b a"); every other grammar here has many
            int minDistinct = text.equals(Fx.LLNOTSLR) ? 2 : 4;
            assertTrue(distinct.size() >= minDistinct, text + " produced only " + distinct.size() + " distinct sentences");
        }
    }

    @Test
    void randomGeneratorIsSeededAndBudgetBoundsLength() {
        Grammar g = Grammar.parse(Fx.LR);
        assertEquals(Generator.random(g, new Random(3), 10), Generator.random(g, new Random(3), 10));
        // with budget 0 the generator must take the minimal-height derivation: a single id
        assertEquals(List.of("id"), Generator.random(g, new Random(1), 0));
        assertEquals(List.of("a"), Generator.random(Grammar.parse(Fx.AMB), new Random(1), 0));
        assertEquals(List.of(), Generator.random(Grammar.parse(Fx.AB), new Random(1), 0));
    }

    private static List<String> corrupt(List<String> w, Random rnd, List<String> alphabet) {
        List<String> c = new ArrayList<>(w);
        int kind = rnd.nextInt(3);
        if (kind == 0 && !c.isEmpty()) {
            c.remove(rnd.nextInt(c.size()));
        } else if (kind == 1 && !c.isEmpty()) {
            c.set(rnd.nextInt(c.size()), alphabet.get(rnd.nextInt(alphabet.size())));
        } else {
            c.add(rnd.nextInt(c.size() + 1), alphabet.get(rnd.nextInt(alphabet.size())));
        }
        return c;
    }

    /** Outcome of one engine: -1 for accepted, otherwise the error index. */
    private static int outcome(java.util.function.Function<List<String>, Tree> parser, List<String> w, Grammar g) {
        try {
            Tree t = parser.apply(w);
            t.validate(g);
            assertEquals(w, t.yield());
            return -1;
        } catch (ParseException e) {
            return e.index();
        }
    }

    @Test
    void ll1SlrAndEarleyAgreeOnEquivalentExpressionGrammars() {
        Grammar gll = Grammar.parse(Fx.LL);
        Grammar glr = Grammar.parse(Fx.LR);
        LL1 ll = LL1.build(gll);
        Slr slr = Slr.build(glr);
        List<String> alphabet = List.of("id", "+", "*", "(", ")");
        Random rnd = new Random(2026);
        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 1500; i++) {
            List<String> w = Generator.random(glr, rnd, 8);
            if (i % 3 != 0) {
                w = corrupt(w, rnd, alphabet);
                if (i % 5 == 0) {
                    w = corrupt(w, rnd, alphabet);
                }
            }
            int oracle = Earley.firstError(glr, w);
            assertEquals(oracle, Earley.firstError(gll, w), "same language " + w);
            assertEquals(oracle, outcome(ll::parse, w, gll), "LL1 on " + w);
            assertEquals(oracle, outcome(slr::parse, w, glr), "SLR on " + w);
            if (oracle < 0) {
                accepted++;
            } else {
                rejected++;
            }
        }
        assertTrue(accepted > 300 && rejected > 300, "mix accepted=" + accepted + " rejected=" + rejected);
    }

    @Test
    void ll1AndSlrBuildIdenticalTreesOnGrammarsThatAreBoth() {
        for (String text : List.of(Fx.SB, Fx.LIST)) {
            Grammar g = Grammar.parse(text);
            LL1 ll = LL1.build(g);
            Slr slr = Slr.build(g);
            assertTrue(ll.isLL1() && slr.isSlr1(), text);
            Random rnd = new Random(5);
            for (int i = 0; i < 200; i++) {
                List<String> w = Generator.random(g, rnd, 8);
                assertEquals(slr.parse(w).sexp(), ll.parse(w).sexp(), text + " on " + w);
            }
        }
    }

    @Test
    void allThreeEnginesAgreeOnEveryShortStringOfTheListGrammar() {
        Grammar g = Grammar.parse(Fx.LIST);
        LL1 ll = LL1.build(g);
        Slr slr = Slr.build(g);
        for (List<String> w : allStrings(g.terminals(), 6)) {
            int oracle = Earley.firstError(g, w);
            assertEquals(oracle, outcome(ll::parse, w, g), "LL1 on " + w);
            assertEquals(oracle, outcome(slr::parse, w, g), "SLR on " + w);
        }
    }

    @Test
    void nullableLl1GrammarMatchesEarleyOnEveryShortString() {
        Grammar g = Grammar.parse(Fx.LLNOTSLR);
        LL1 ll = LL1.build(g);
        for (List<String> w : allStrings(g.terminals(), 5)) {
            assertEquals(Earley.firstError(g, w), outcome(ll::parse, w, g), "LL1 on " + w);
        }
        Grammar paren = Grammar.parse("S -> ( S ) S | eps\n");
        LL1 pl = LL1.build(paren);
        Slr ps = Slr.build(paren);
        for (List<String> w : allStrings(paren.terminals(), 8)) {
            int oracle = Earley.firstError(paren, w);
            assertEquals(oracle, outcome(pl::parse, w, paren), "LL1 on " + w);
            assertEquals(oracle, outcome(ps::parse, w, paren), "SLR on " + w);
        }
    }

    @Test
    void deepInputsDoNotOverflowAndStayFast() {
        StringBuilder sb = new StringBuilder("id");
        for (int i = 0; i < 1000; i++) {
            sb.append(" + id * id");
        }
        List<String> w = Fx.toks(sb.toString());
        Grammar gll = Grammar.parse(Fx.LL);
        Grammar glr = Grammar.parse(Fx.LR);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            Tree a = LL1.build(gll).parse(w);
            Tree b = Slr.build(glr).parse(w);
            assertEquals(w, a.yield());
            assertEquals(w, b.yield());
            assertTrue(Earley.recognizes(glr, w.subList(0, 301)));
        });
    }
}
