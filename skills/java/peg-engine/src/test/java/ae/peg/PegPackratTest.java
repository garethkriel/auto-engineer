package ae.peg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PegPackratTest {
    /** Each level tries the same sub-rule twice, so without memoisation work doubles per level. */
    private static final String BLOWUP = """
            S <- A 'x' / A 'y'
            A <- B 'a' / B 'b'
            B <- C 'a' / C 'b'
            C <- D 'a' / D 'b'
            D <- E 'a' / E 'b'
            E <- F 'a' / F 'b'
            F <- G 'a' / G 'b'
            G <- 'a' / 'b'
            """;

    @Test
    void memoisedEvaluationsAreBoundedByRulesTimesPositions() {
        Grammar g = Grammar.parse(BLOWUP);
        String in = "bbbbbbbz"; // alt 1 of every level fails after its sub-rule succeeded, so alt 2 re-runs it
        PegParser.Result r = new PegParser(g).run(in);
        int bound = g.rules().size() * (in.length() + 1);
        assertTrue(r.evaluations() <= bound, r.evaluations() + " > " + bound);
        assertTrue(r.node().isEmpty());
    }

    @Test
    void withoutMemoisationTheSameInputDoesFarMoreWork() {
        Grammar g = Grammar.parse(BLOWUP);
        String in = "bbbbbbbz";
        int memo = new PegParser(g, true).run(in).evaluations();
        int plain = new PegParser(g, false).run(in).evaluations();
        assertTrue(plain >= 5 * memo, "plain=" + plain + " memo=" + memo);
    }

    @Test
    void memoisedAndPlainAgreeOnTreesAndErrors() {
        Grammar g = Grammar.parse(BLOWUP);
        for (String in : new String[] {"bbbbbbbx", "bbbbbbby", "abababay", "bbbbbbbz", "ab", "", "bbbbbbbyy"}) {
            PegParser.Result a = new PegParser(g, true).run(in);
            PegParser.Result b = new PegParser(g, false).run(in);
            assertEquals(b.node(), a.node(), in);
            assertEquals(b.farthest(), a.farthest(), in);
            assertEquals(b.expected(), a.expected(), in);
        }
    }

    @Test
    void longInputStaysLinear() {
        Grammar g = Grammar.parse(PegGrammarsTest.ARITH);
        StringBuilder sb = new StringBuilder("1");
        for (int i = 0; i < 2000; i++) {
            sb.append(i % 2 == 0 ? "+" : "*").append(i % 10);
        }
        String in = sb.toString();
        PegParser.Result r = new PegParser(g).run(in);
        assertEquals(in.length(), r.node().orElseThrow().end());
        assertTrue(r.evaluations() <= g.rules().size() * (in.length() + 1));
    }

    @Test
    void predicateScopedMemoDoesNotHideExpectations() {
        // Item is first tried inside !Item (silent, fails, so the predicate succeeds), then for real at the same
        // position after 'q' fails: its terminal failure must still be reported by the real attempt.
        Grammar g = Grammar.parse("S <- !Item 'q' / Item\nItem <- 'a' 'b'");
        for (boolean memo : new boolean[] {true, false}) {
            PegParseException e = org.junit.jupiter.api.Assertions.assertThrows(PegParseException.class,
                    () -> new PegParser(g, memo).parse("ac"));
            assertEquals(1, e.position());
            assertEquals(List.of("'b'"), List.copyOf(e.expected()));
        }
    }
}
