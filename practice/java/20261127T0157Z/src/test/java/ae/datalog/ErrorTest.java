package ae.datalog;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ErrorTest {
    static String msg(String src) {
        return assertThrows(DatalogException.class, () -> new Engine(Parser.parse(src))).getMessage();
    }

    @Test
    void controlParsesFine() {
        assertDoesNotThrow(() -> new Engine(Parser.parse("p(X) :- q(X), not r(X), X != a.\nq(a). r(b). % c\n?- p(Y).")));
        assertEquals(1, Parser.parse("?- p(Y). p(a).").queries().size());
    }

    @Test
    void syntaxErrorsCarryLineAndColumn() {
        assertEquals("2:6: expected '.' or ':-'", msg("p(a).\nq(b) r(c)."));
        assertTrue(msg("p(a").startsWith("1:4: "), msg("p(a"));
        assertTrue(msg("p(a).\n  P(a).").startsWith("2:3: "), msg("p(a).\n  P(a)."));
        assertTrue(msg("p(a) :- .").startsWith("1:9: "), msg("p(a) :- ."));
        assertTrue(msg("p(a) #").startsWith("1:6: "), msg("p(a) #"));
        assertTrue(msg("p(a) :- not X = a.").contains("1:"), "negated comparison unsupported");
    }

    @Test
    void unsafeRules() {
        assertTrue(msg("p(X).").startsWith("unsafe"), msg("p(X)."));
        assertTrue(msg("q(a). p(X, Y) :- q(X).").contains("Y"));
        assertTrue(msg("q(a). p(X) :- q(X), not r(Y). r(a).").contains("Y"));
        assertTrue(msg("q(a). p(X) :- q(X), X != Y.").contains("Y"));
        assertTrue(msg("q(a). p(X) :- q(X), Y = a.").contains("Y"));
        assertTrue(msg("q(a). r(a). p(X) :- q(X), not r(_).").startsWith("unsafe"));
        // controls differing by the single defect
        assertDoesNotThrow(() -> new Engine(Parser.parse("q(a). r(a). p(X) :- q(X), not r(X).")));
        assertDoesNotThrow(() -> new Engine(Parser.parse("q(a). p(X) :- q(X), X != a.")));
    }

    @Test
    void arityMismatch() {
        assertTrue(msg("p(a). p(a, b).").startsWith("arity"), msg("p(a). p(a, b)."));
        assertTrue(msg("p(a). q(X) :- p(X, X).").startsWith("arity"));
        assertTrue(msg("p(a). ?- p(a, b).").startsWith("arity"));
    }

    @Test
    void nonStratifiableNamesCycle() {
        String m = msg("a(x). p(X) :- a(X), not q(X). q(X) :- a(X), not p(X).");
        assertTrue(m.startsWith("not stratifiable") && m.contains("p") && m.contains("q"), m);
        // control: same shape without the negation closing the cycle
        assertDoesNotThrow(() -> new Engine(Parser.parse("a(x). p(X) :- a(X), not q(X). q(X) :- a(X).")));
    }

    @Test
    void queryArityCheckedAgainstEngineAndSelfNegationRejected() {
        Engine e = new Engine(Parser.parse("p(a, b)."));
        DatalogException ex = assertThrows(DatalogException.class, () -> e.query(Parser.parseAtom("p(a)")));
        assertTrue(ex.getMessage().startsWith("arity"), ex.getMessage());
        assertEquals(1, e.query(Parser.parseAtom("p(a, X)")).size());
        // self negation: the 'h.equals(b)' test is masked by reach(p) containing p, so this guards the combined rule
        assertTrue(msg("n(a). p(X) :- n(X), not p(X).").startsWith("not stratifiable"));
    }
}
