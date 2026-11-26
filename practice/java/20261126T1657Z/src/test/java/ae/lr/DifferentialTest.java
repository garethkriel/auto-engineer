package ae.lr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Differential tests against an oracle that knows nothing about LR tables: the set of all
 * sentences up to a length bound, computed as a least fixed point over the productions.
 */
class DifferentialTest {

    /** All sentences (token lists) of length <= cap derivable from each nonterminal. */
    static Map<String, Set<List<String>>> languages(Grammar g, int cap) {
        Map<String, Set<List<String>>> lang = new HashMap<>();
        for (String n : g.nonterminals()) {
            lang.put(n, new HashSet<>());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : g.productions().subList(1, g.productions().size())) {
                Set<List<String>> acc = new HashSet<>();
                acc.add(List.of());
                for (String sym : p.rhs()) {
                    Set<List<String>> part = g.nonterminals().contains(sym)
                        ? lang.get(sym) : Set.of(List.of(sym));
                    Set<List<String>> next = new HashSet<>();
                    for (List<String> a : acc) {
                        for (List<String> b : part) {
                            if (a.size() + b.size() <= cap) {
                                List<String> c = new ArrayList<>(a);
                                c.addAll(b);
                                next.add(c);
                            }
                        }
                    }
                    acc = next;
                }
                changed |= lang.get(p.lhs()).addAll(acc);
            }
        }
        return lang;
    }

    static void allStrings(List<String> alphabet, int maxLen, List<String> cur, List<List<String>> out) {
        out.add(List.copyOf(cur));
        if (cur.size() == maxLen) {
            return;
        }
        for (String a : alphabet) {
            cur.add(a);
            allStrings(alphabet, maxLen, cur, out);
            cur.remove(cur.size() - 1);
        }
    }

    static Set<List<String>> prefixes(Set<List<String>> sentences) {
        Set<List<String>> out = new HashSet<>();
        for (List<String> s : sentences) {
            for (int i = 0; i <= s.size(); i++) {
                out.add(List.copyOf(s.subList(0, i)));
            }
        }
        return out;
    }

    /** Every node's symbol/children spell out a production; the yield equals the input. */
    static void checkTree(Grammar g, Tree t, List<String> input) {
        assertEquals(input, t.yield());
        validNode(g, t);
    }

    static void validNode(Grammar g, Tree t) {
        if (t.isLeaf()) {
            assertTrue(g.terminals().contains(t.symbol()), t.symbol());
            return;
        }
        Production p = g.productions().get(t.production());
        assertEquals(p.lhs(), t.symbol());
        List<String> kids = new ArrayList<>();
        for (Tree c : t.children()) {
            kids.add(c.symbol());
        }
        assertEquals(p.rhs(), kids);
        for (Tree c : t.children()) {
            validNode(g, c);
        }
    }

    @Test
    void expressionGrammarMatchesOracleOnEveryStringUpToLengthSix() {
        Grammar g = Grammar.parse(GrammarTest.EXPR);
        Set<List<String>> sentences = languages(g, 9).get("E");
        Set<List<String>> viable = prefixes(sentences);
        assertTrue(sentences.contains(List.of("id")));
        assertTrue(sentences.contains(List.of("(", "(", "id", ")", ")")));
        assertFalse(sentences.contains(List.of("id", "id")));
        List<List<String>> inputs = new ArrayList<>();
        allStrings(List.of("id", "+", "*", "(", ")"), 6, new ArrayList<>(), inputs);
        assertEquals(1 + 5 + 25 + 125 + 625 + 3125 + 15625, inputs.size());

        LrParser lr1 = new LrParser(LrTable.build(g, Mode.LR1));
        LrParser lalr = new LrParser(LrTable.build(g, Mode.LALR));
        LrParser slr = new LrParser(LrTable.build(g, Mode.SLR));
        int accepted = 0;
        for (List<String> in : inputs) {
            boolean member = sentences.contains(in);
            Tree t1 = null;
            LrParseException e1 = null;
            try {
                t1 = lr1.parse(in);
            } catch (LrParseException e) {
                e1 = e;
            }
            assertEquals(member, t1 != null, in.toString());
            if (member) {
                accepted++;
                checkTree(g, t1, in);
                assertEquals(t1.toString(), lalr.parse(in).toString());
                assertEquals(t1.toString(), slr.parse(in).toString());
            } else {
                for (LrParser other : List.of(lalr, slr)) {
                    try {
                        other.parse(in);
                        throw new AssertionError("accepted " + in);
                    } catch (LrParseException e) {
                        assertEquals(e1.position(), e.position(), in.toString());
                        assertEquals(e1.found(), e.found());
                    }
                }
                // canonical LR(1) has the correct-prefix property: the prefix before the error is
                // viable and one more token is not (checked only where the length bound is exact:
                // a prefix of <= 4 tokens needs a completion of <= 9 tokens)
                int pos = e1.position();
                if (pos + 1 <= 4) {
                    assertTrue(viable.contains(in.subList(0, pos)), in.toString());
                    if (pos < in.size()) {
                        assertFalse(viable.contains(in.subList(0, pos + 1)), in.toString());
                    }
                }
                // every token that continues a viable prefix is in the expected set
                for (String tok : List.of("id", "+", "*", "(", ")")) {
                    List<String> ext = new ArrayList<>(in.subList(0, pos));
                    ext.add(tok);
                    if (pos + 1 <= 4 && viable.contains(ext)) {
                        assertTrue(e1.expected().contains(tok), in + " lacks " + tok);
                    }
                }
            }
        }
        assertEquals(sentences.stream().filter(s -> s.size() <= 6).count(), accepted);
        // sentences of length 1, 3, 5: 1 + 3 (id+id, id*id, (id)) + 11 (id o id o id: 4,
        // (id) o id: 2, id o (id): 2, ((id)): 1, (id o id): 2)
        assertEquals(15, accepted);
    }

    @Test
    void epsilonGrammarMatchesOracle() {
        Grammar g = Grammar.parse("""
            S -> A B
            A -> a A | ε
            B -> b B c | ε
            """);
        Set<List<String>> sentences = languages(g, 7).get("S");
        List<List<String>> inputs = new ArrayList<>();
        allStrings(List.of("a", "b", "c"), 7, new ArrayList<>(), inputs);
        LrParser p = new LrParser(LrTable.build(g, Mode.LALR));
        int accepted = 0;
        for (List<String> in : inputs) {
            boolean ok;
            try {
                checkTree(g, p.parse(in), in);
                ok = true;
                accepted++;
            } catch (LrParseException e) {
                ok = false;
            }
            assertEquals(sentences.contains(in), ok, in.toString());
        }
        // a^i (b^j c^j), total length <= 7: sum over j of (7 - 2j + 1) = 8 + 6 + 4 + 2
        assertEquals(20, accepted);
    }

    // ---------------------------------------------------------------- random grammars

    static String randomGrammar(Random r) {
        String[] nts = {"S", "A", "B"};
        String[] ts = {"a", "b", "c"};
        StringBuilder sb = new StringBuilder();
        for (String lhs : nts) {
            int alts = 1 + r.nextInt(3);
            sb.append(lhs).append(" ->");
            for (int i = 0; i < alts; i++) {
                if (i > 0) {
                    sb.append(" |");
                }
                int len = r.nextInt(4);
                if (len == 0) {
                    sb.append(" ε");
                }
                for (int k = 0; k < len; k++) {
                    // terminals twice as likely as nonterminals
                    boolean term = r.nextInt(3) != 0;
                    sb.append(' ').append(term ? ts[r.nextInt(3)] : nts[r.nextInt(3)]);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static boolean free(Grammar g, Mode m) {
        return LrTable.build(g, m).conflicts().isEmpty();
    }

    @Test
    void randomGrammarsSatisfyTheModeHierarchyAndMatchTheOracle() {
        Random r = new Random(20261126L);
        int slrFree = 0;
        int lalrFree = 0;
        int lr1Free = 0;
        int lr1OnlyFree = 0;
        int parsersCompared = 0;
        int nonEmptyLanguages = 0;
        List<List<String>> inputs = new ArrayList<>();
        allStrings(List.of("a", "b", "c"), 5, new ArrayList<>(), inputs);
        for (int iter = 0; iter < 400; iter++) {
            String text = randomGrammar(r);
            Grammar g = Grammar.parse(text);
            LrTable slr = LrTable.build(g, Mode.SLR);
            LrTable lalr = LrTable.build(g, Mode.LALR);
            LrTable lr1 = LrTable.build(g, Mode.LR1);
            // theorem: LALR(1) has exactly the LR(0) states
            assertEquals(slr.stateCount(), lalr.stateCount(), text);
            assertTrue(lr1.stateCount() >= lalr.stateCount(), text);
            // hierarchy of grammar classes
            if (slr.conflicts().isEmpty()) {
                slrFree++;
                assertTrue(lalr.conflicts().isEmpty(), text);
            }
            if (lalr.conflicts().isEmpty()) {
                lalrFree++;
                assertTrue(lr1.conflicts().isEmpty(), text);
            }
            if (!lr1.conflicts().isEmpty()) {
                continue;
            }
            lr1Free++;
            if (!lalr.conflicts().isEmpty()) {
                lr1OnlyFree++;
            }
            Set<List<String>> sentences = languages(g, 5).get("S");
            if (!sentences.isEmpty()) {
                nonEmptyLanguages++;
            }
            LrParser p1 = new LrParser(lr1);
            LrParser pl = lalr.conflicts().isEmpty() ? new LrParser(lalr) : null;
            for (List<String> in : inputs) {
                Tree t1 = null;
                LrParseException e1 = null;
                try {
                    t1 = p1.parse(in);
                } catch (LrParseException e) {
                    e1 = e;
                }
                assertEquals(sentences.contains(in), t1 != null, text + " input " + in);
                if (t1 != null) {
                    checkTree(g, t1, in);
                }
                if (pl != null) {
                    parsersCompared++;
                    try {
                        Tree tl = pl.parse(in);
                        assertEquals(t1.toString(), tl.toString(), text + " input " + in);
                    } catch (LrParseException e) {
                        assertTrue(e1 != null, text + " input " + in);
                        assertEquals(e1.position(), e.position(), text + " input " + in);
                        assertFalse(e.expected().contains(e.found()));
                    }
                }
            }
        }
        // measured mix of the seeded run (guards against a generator that never exercises a branch)
        System.out.println("random grammars: slrFree=" + slrFree + " lalrFree=" + lalrFree
            + " lr1Free=" + lr1Free + " lr1OnlyFree=" + lr1OnlyFree
            + " nonEmpty=" + nonEmptyLanguages + " compared=" + parsersCompared);
        assertTrue(slrFree >= MIN_SLR, "slrFree=" + slrFree);
        assertTrue(lalrFree > slrFree, "lalrFree=" + lalrFree + " slrFree=" + slrFree);
        assertTrue(lr1Free >= MIN_LR1, "lr1Free=" + lr1Free);
        assertTrue(nonEmptyLanguages >= MIN_NONEMPTY, "nonEmpty=" + nonEmptyLanguages);
        assertTrue(parsersCompared >= MIN_COMPARED, "compared=" + parsersCompared);
    }

    static final int MIN_SLR = 100;
    static final int MIN_LR1 = 100;
    static final int MIN_NONEMPTY = 100;
    static final int MIN_COMPARED = 10_000;
}
