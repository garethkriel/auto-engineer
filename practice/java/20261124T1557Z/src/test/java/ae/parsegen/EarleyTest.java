package ae.parsegen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EarleyTest {
    @Test
    void recognisesExpressionsWithLeftRecursion() {
        Grammar g = Grammar.parse(Fx.LR);
        assertTrue(Earley.recognizes(g, Fx.toks("id + id * ( id + id )")));
        assertFalse(Earley.recognizes(g, Fx.toks("id + * id")));
        assertFalse(Earley.recognizes(g, List.of()));
    }

    @Test
    void nullableGrammarMatchesIndependentLanguageDescription() {
        // language of S -> A S b | eps, A -> a | eps is { a^i b^k : i <= k }
        Grammar g = Grammar.parse(Fx.AB);
        for (int len = 0; len <= 9; len++) {
            for (int bits = 0; bits < (1 << len); bits++) {
                List<String> w = new ArrayList<>();
                int as = 0;
                int bs = 0;
                boolean sorted = true;
                for (int i = 0; i < len; i++) {
                    boolean isB = ((bits >> i) & 1) == 1;
                    w.add(isB ? "b" : "a");
                    if (isB) {
                        bs++;
                    } else {
                        as++;
                        if (bs > 0) {
                            sorted = false;
                        }
                    }
                }
                boolean expected = sorted && as <= bs;
                assertEquals(expected, Earley.recognizes(g, w), String.join(" ", w));
            }
        }
    }

    @Test
    void firstErrorIsViablePrefixBoundary() {
        Grammar g = Grammar.parse(Fx.SB); // S -> a S b | c : a^n c b^n
        assertEquals(-1, Earley.firstError(g, Fx.toks("a a c b b")));
        assertEquals(4, Earley.firstError(g, Fx.toks("a a c b"))); // every prefix viable, only EOF is wrong
        assertEquals(5, Earley.firstError(g, Fx.toks("a a c b b b"))); // third b is surplus
        assertEquals(2, Earley.firstError(g, Fx.toks("a c c b"))); // after "a c" only b may follow
        assertEquals(0, Earley.firstError(g, Fx.toks("b")));
        assertEquals(0, Earley.firstError(g, List.of()));
        assertEquals(0, Earley.firstError(g, Fx.toks("zz")));
    }

    @Test
    void ambiguousGrammarIsRecognisedOnce() {
        Grammar g = Grammar.parse(Fx.AMB);
        assertTrue(Earley.recognizes(g, Fx.toks("a + a + a + a")));
        assertFalse(Earley.recognizes(g, Fx.toks("a + + a")));
    }

    @Test
    void cyclicUnitProductionsTerminate() {
        Grammar g = Grammar.parse("A -> B | a\nB -> A\n");
        assertTrue(Earley.recognizes(g, Fx.toks("a")));
        assertFalse(Earley.recognizes(g, Fx.toks("a a")));
    }

    @Test
    void hiddenLeftRecursionThroughNullablePrefix() {
        // S -> N S x | y, N -> eps : left recursion hidden behind a nullable symbol
        Grammar g = Grammar.parse("S -> N S x | y\nN -> eps\n");
        assertTrue(Earley.recognizes(g, Fx.toks("y x x x")));
        assertFalse(Earley.recognizes(g, Fx.toks("x y")));
        assertEquals(1, Earley.firstError(g, Fx.toks("y y")));
    }
}
