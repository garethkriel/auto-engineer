package ae.datalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class EngineTest {
    static Engine engine(String src) {
        return new Engine(Parser.parse(src));
    }

    static Set<List<String>> rel(Engine e, Engine.Mode m, String pred) {
        return e.evaluate(m).getOrDefault(pred, Set.of());
    }

    static final String FAMILY = """
        parent(tom, bob). parent(tom, liz). parent(bob, ann). parent(bob, pat). parent(pat, jim).
        anc(X, Y) :- parent(X, Y).
        anc(X, Z) :- parent(X, Y), anc(Y, Z).
        """;

    @Test
    void ancestorsLiteral() {
        Engine e = engine(FAMILY);
        for (Engine.Mode m : Engine.Mode.values()) {
            Set<List<String>> a = rel(e, m, "anc");
            assertEquals(
                Set.of(List.of("tom", "bob"), List.of("tom", "liz"), List.of("bob", "ann"), List.of("bob", "pat"),
                    List.of("pat", "jim"), List.of("tom", "ann"), List.of("tom", "pat"), List.of("bob", "jim"),
                    List.of("tom", "jim")),
                a, m.toString());
        }
    }

    @Test
    void queryBindingsSortedAndGround() {
        Engine e = engine(FAMILY);
        e.evaluate(Engine.Mode.SEMI_NAIVE);
        List<Map<String, String>> r = e.query(Parser.parseAtom("anc(tom, X)"));
        assertEquals(List.of(Map.of("X", "ann"), Map.of("X", "bob"), Map.of("X", "jim"), Map.of("X", "liz"),
            Map.of("X", "pat")), r);
        assertEquals(List.of(Map.of()), e.query(Parser.parseAtom("anc(tom, jim)")));
        assertEquals(List.of(), e.query(Parser.parseAtom("anc(jim, tom)")));
        // repeated variable in a query must match equal values only
        assertEquals(List.of(), e.query(Parser.parseAtom("anc(X, X)")));
    }

    @Test
    void integersSortNumericallyBeforeAtoms() {
        Engine e = engine("v(10). v(9). v(-3). v(b). v(a). v(100).");
        e.evaluate(Engine.Mode.NAIVE);
        List<String> got = new ArrayList<>();
        for (Map<String, String> m : e.query(Parser.parseAtom("v(X)"))) got.add(m.get("X"));
        assertEquals(List.of("-3", "9", "10", "100", "a", "b"), got);
    }

    @Test
    void sameGenerationClassic() {
        // levels: a -> b,c ; b -> d,e ; c -> f
        Engine e = engine("""
            up(d, b). up(e, b). up(b, a). up(c, a). up(f, c).
            flat(b, c).
            down(b, d). down(b, e). down(a, b). down(a, c). down(c, f).
            sg(X, Y) :- flat(X, Y).
            sg(X, Y) :- up(X, X1), sg(X1, Y1), down(Y1, Y).
            """);
        Set<List<String>> expect = Set.of(List.of("b", "c"), List.of("d", "f"), List.of("e", "f"));
        // sg(d,f): up(d,b), sg(b,c), down(c,f). sg(e,f) likewise. sg(b,c) is the flat edge.
        for (Engine.Mode m : Engine.Mode.values()) assertEquals(expect, rel(e, m, "sg"), m.toString());
    }

    @Test
    void stratifiedNegationUnreachable() {
        Engine e = engine("""
            node(a). node(b). node(c). node(d).
            edge(a, b). edge(b, c). edge(d, d).
            reach(a).
            reach(Y) :- reach(X), edge(X, Y).
            unreach(X) :- node(X), not reach(X).
            """);
        for (Engine.Mode m : Engine.Mode.values()) {
            assertEquals(Set.of(List.of("a"), List.of("b"), List.of("c")), rel(e, m, "reach"));
            assertEquals(Set.of(List.of("d")), rel(e, m, "unreach"));
        }
    }

    @Test
    void winMoveIsRejectedAsUnstratified() {
        DatalogException ex = assertThrows(DatalogException.class,
            () -> engine("move(a,b). win(X) :- move(X,Y), not win(Y)."));
        assertTrue(ex.getMessage().startsWith("not stratifiable"), ex.getMessage());
        assertTrue(ex.getMessage().contains("win"), ex.getMessage());
    }

    @Test
    void negationThroughTwoPredicatesIsRejectedButPositiveCycleIsFine() {
        assertThrows(DatalogException.class, () -> engine("n(a). p(X) :- n(X), not q(X). q(X) :- n(X), p(X)."));
        Engine ok = engine("n(a). p(X) :- n(X), q(X). q(X) :- n(X), p(X). q(a).");
        assertEquals(Set.of(List.of("a")), rel(ok, Engine.Mode.SEMI_NAIVE, "p"));
    }

    @Test
    void equalityAndInequality() {
        Engine e = engine("""
            e(1, 1). e(1, 2). e(2, 3).
            loop(X) :- e(X, Y), X = Y.
            proper(X, Y) :- e(X, Y), X != Y.
            from1(Y) :- e(X, Y), X = 1, Y != 1.
            """);
        for (Engine.Mode m : Engine.Mode.values()) {
            assertEquals(Set.of(List.of("1")), rel(e, m, "loop"));
            assertEquals(Set.of(List.of("1", "2"), List.of("2", "3")), rel(e, m, "proper"));
            assertEquals(Set.of(List.of("2")), rel(e, m, "from1"));
        }
    }

    @Test
    void anonymousVariablesAreIndependent() {
        Engine e = engine("t(a, b, c). t(a, b, b). s(X) :- t(X, _, _). d(X) :- t(X, Y, Y).");
        assertEquals(Set.of(List.of("a")), rel(e, Engine.Mode.NAIVE, "s"));
        // d needs Y equal in both positions: only t(a,b,b)
        assertEquals(Set.of(List.of("a")), rel(e, Engine.Mode.NAIVE, "d"));
        Engine f = engine("t(a, b, c). u(X) :- t(_, X, _).  w(X) :- t(_, _, X).");
        assertEquals(Set.of(List.of("b")), rel(f, Engine.Mode.NAIVE, "u"));
        assertEquals(Set.of(List.of("c")), rel(f, Engine.Mode.NAIVE, "w"));
    }

    @Test
    void factsSurviveWithoutRulesAndEmptyProgramIsFine() {
        assertEquals(Map.of(), engine("").evaluate(Engine.Mode.NAIVE));
        assertEquals(Set.of(List.of("x")), rel(engine("p(x)."), Engine.Mode.SEMI_NAIVE, "p"));
    }

    @Test
    void zeroAryPredicates() {
        Engine e = engine("rain. wet :- rain. dry :- not rain. sun :- not wet.");
        for (Engine.Mode m : Engine.Mode.values()) {
            assertEquals(Set.of(List.of()), rel(e, m, "wet"));
            assertEquals(Set.of(), rel(e, m, "dry"));
            assertEquals(Set.of(), rel(e, m, "sun"));
        }
    }

    @Test
    void semiNaiveDoesLessWorkOnLongChain() {
        StringBuilder sb = new StringBuilder("path(X,Y) :- edge(X,Y). path(X,Z) :- path(X,Y), edge(Y,Z).\n");
        for (int i = 0; i < 40; i++) sb.append("edge(").append(i).append(',').append(i + 1).append(").\n");
        Engine e = engine(sb.toString());
        Model naive = e.evaluate(Engine.Mode.NAIVE);
        long wn = e.stats();
        Model semi = e.evaluate(Engine.Mode.SEMI_NAIVE);
        long ws = e.stats();
        assertEquals(naive, semi);
        assertEquals(41 * 40 / 2, semi.get("path").size()); // pairs i<j over nodes 0..40
        assertTrue(ws * 5 < wn, "semi=" + ws + " naive=" + wn);
    }

    @Test
    void evaluateIsRepeatableAndIndependent() {
        Engine e = engine(FAMILY);
        Model a = e.evaluate(Engine.Mode.SEMI_NAIVE);
        Model b = e.evaluate(Engine.Mode.SEMI_NAIVE);
        assertEquals(a, b);
        assertThrows(UnsupportedOperationException.class, () -> a.get("anc").add(List.of("q", "q")));
    }
}
